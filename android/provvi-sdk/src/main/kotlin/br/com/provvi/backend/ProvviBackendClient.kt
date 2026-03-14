package br.com.provvi.backend

import android.util.Log
import br.com.provvi.CaptureSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "ProvviBackendClient"

// ---------------------------------------------------------------------------
// Configuração
// ---------------------------------------------------------------------------

// TODO: preencher após abertura de conta corporativa na Play Store.
//       Obter em: console.cloud.google.com → APIs e Serviços → Play Integrity API.
//       Enquanto 0L, o pré-aquecimento da Play Integrity é ignorado e o campo
//       "Play Integrity" no manifesto C2PA ficará como "Indisponível".
private const val PROVVI_DEFAULT_CLOUD_PROJECT = 0L

/**
 * Parâmetros de conexão com o backend de armazenamento de sessões.
 *
 * @param lambdaUrl            URL da Lambda (Function URL direta ou API Gateway).
 *                             Exemplo: "https://xyz.lambda-url.sa-east-1.on.aws/"
 * @param timeoutSeconds       Timeout total da requisição HTTP em segundos. Padrão: 30.
 * @param apiKey               Chave de autenticação enviada no header x-api-key.
 * @param cloudProjectNumber   Número do projeto Google Cloud com a Play Integrity API habilitada.
 *                             Padrão: projeto Provvi (configurado internamente).
 *                             Integradores com projeto próprio podem substituir este valor.
 *
 *                             **Atenção (DT-020):** deve ser o mesmo valor de
 *                             [br.com.provvi.ProvviConfig.cloudProjectNumber].
 *                             Manter os dois em sincronia ao preencher o número real de produção.
 */
data class BackendConfig(
    val lambdaUrl:           String,
    val timeoutSeconds:      Long   = 30,
    val apiKey:              String = "",
    val cloudProjectNumber:  Long   = PROVVI_DEFAULT_CLOUD_PROJECT
)

// ---------------------------------------------------------------------------
// Resultado do upload
// ---------------------------------------------------------------------------

/**
 * Classificação de erros de backend para tratamento programático (DT-017).
 */
enum class BackendErrorType {
    /** Erro genérico de servidor ou rede. */
    GENERIC,
    /** Backend retornou 503 com body `"error": "TSA_UNAVAILABLE"` — âncora temporal indisponível. */
    TSA_UNAVAILABLE,
    /** Backend retornou 401 — API Key inválida ou expirada (DT-005/008). */
    AUTH_FAILED,
    /** Backend retornou 403 — Key Attestation detectou bootloader unlocked ou verified boot falhou. */
    DEVICE_COMPROMISED
}

/**
 * Resultado do envio de uma sessão de captura ao backend.
 */
sealed class BackendResult {

    /**
     * Upload realizado com sucesso — sessão registrada no S3 e DynamoDB.
     *
     * @param sessionId    UUID da sessão confirmado pelo backend.
     * @param manifestUrl  URL presigned do manifesto C2PA no S3 (válida 7 dias).
     * @param processedAt  Timestamp de processamento no servidor (RFC 3339).
     */
    data class Success(
        val sessionId:   String,
        val manifestUrl: String,
        val processedAt: String
    ) : BackendResult()

    /**
     * Falha no upload.
     *
     * @param message     Descrição do erro para log interno — não exibir ao usuário final.
     * @param isRetryable true para erros 5xx e falhas de rede (retry faz sentido);
     *                    false para erros 4xx (payload inválido, sem retry).
     * @param errorType   Classificação do erro para tratamento programático (DT-017).
     */
    data class Error(
        val message:     String,
        val isRetryable: Boolean,
        val errorType:   BackendErrorType = BackendErrorType.GENERIC
    ) : BackendResult()
}

// ---------------------------------------------------------------------------
// Cliente HTTP
// ---------------------------------------------------------------------------

/**
 * Envia sessões de captura autenticadas para o backend Lambda (S3 + DynamoDB).
 *
 * Não bloqueia a thread chamadora — toda I/O é realizada em [Dispatchers.IO].
 * Falhas de upload são reportadas via [BackendResult.Error] e NÃO invalidam a sessão
 * local, que permanece válida independentemente do resultado do envio.
 *
 * Serialização JSON feita manualmente via [org.json.JSONObject] — sem Gson/Moshi,
 * mantendo o SDK livre de dependências de serialização de terceiros.
 *
 * @param config Configuração de URL e timeout.
 */
class ProvviBackendClient(private val config: BackendConfig) {

    // Cliente OkHttp compartilhado — thread-safe, reutilizável entre chamadas
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .build()

    /**
     * Serializa e envia uma [CaptureSession] ao backend de armazenamento.
     *
     * O campo [CaptureSession.imageJpegBytes] é codificado em Base64 antes do envio.
     * O timeout total (connect + read + write) é configurado via [BackendConfig.timeoutSeconds].
     *
     * @param session Sessão produzida por [br.com.provvi.ProvviCapture.capture].
     * @return [BackendResult.Success] com a URL do manifesto no S3, ou
     *         [BackendResult.Error] com indicação de retentatibilidade.
     */
    suspend fun upload(session: CaptureSession): BackendResult = withContext(Dispatchers.IO) {
        try {
            val body = buildRequestBody(session)
            val requestBuilder = Request.Builder()
                .url(config.lambdaUrl)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (config.apiKey.isNotEmpty()) {
                requestBuilder.addHeader("x-api-key", config.apiKey)
            }

            val request = requestBuilder.build()

            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            when (responseCode) {
                in 200..299 -> parseSuccess(responseBody)
                401 -> {
                    Log.w(TAG, "Erro 401 ao enviar sessão ${session.sessionId}: API Key inválida ou expirada")
                    BackendResult.Error(
                        message     = "API Key inválida ou expirada",
                        isRetryable = false,
                        errorType   = BackendErrorType.AUTH_FAILED
                    )
                }
                403 -> {
                    Log.w(TAG, "Erro 403 ao enviar sessão ${session.sessionId}: dispositivo comprometido (Key Attestation)")
                    BackendResult.Error(
                        message     = "Dispositivo comprometido detectado — bootloader desbloqueado ou verified boot falhou",
                        isRetryable = false,
                        errorType   = BackendErrorType.DEVICE_COMPROMISED
                    )
                }
                in 400..499 -> {
                    // Erro de cliente — payload inválido, sem retry
                    Log.w(TAG, "Erro HTTP $responseCode ao enviar sessão ${session.sessionId}: $responseBody")
                    BackendResult.Error(
                        message     = "Erro HTTP $responseCode: $responseBody",
                        isRetryable = false
                    )
                }
                503 -> {
                    // Verificar se é falha de TSA (DT-017)
                    val isTsaError = try {
                        org.json.JSONObject(responseBody).getString("error") == "TSA_UNAVAILABLE"
                    } catch (_: Exception) { false }

                    if (isTsaError) {
                        Log.w(TAG, "TSA indisponível ao enviar sessão ${session.sessionId}")
                        BackendResult.Error(
                            message     = "TSA indisponível — âncora temporal não obtida. Tente novamente.",
                            isRetryable = true,
                            errorType   = BackendErrorType.TSA_UNAVAILABLE
                        )
                    } else {
                        Log.w(TAG, "Erro HTTP 503 ao enviar sessão ${session.sessionId}: $responseBody")
                        BackendResult.Error(
                            message     = "Serviço temporariamente indisponível: $responseBody",
                            isRetryable = true
                        )
                    }
                }
                else -> {
                    // Outros erros de servidor (5xx) — retry pode resolver
                    Log.w(TAG, "Erro HTTP $responseCode ao enviar sessão ${session.sessionId}: $responseBody")
                    BackendResult.Error(
                        message     = "Erro HTTP $responseCode: $responseBody",
                        isRetryable = true
                    )
                }
            }
        } catch (e: IOException) {
            // Falha de rede (timeout, sem conectividade) — sempre retryable
            Log.w(TAG, "Falha de rede ao enviar sessão ${session.sessionId}: ${e.message}")
            BackendResult.Error(
                message     = "Falha de rede: ${e.message}",
                isRetryable = true
            )
        } catch (e: Exception) {
            // Falha inesperada na serialização ou parsing — não retryable sem correção
            Log.e(TAG, "Erro inesperado ao enviar sessão ${session.sessionId}: ${e.message}", e)
            BackendResult.Error(
                message     = "Erro inesperado: ${e.message}",
                isRetryable = false
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Funções auxiliares privadas
    // ---------------------------------------------------------------------------

    /**
     * Monta o corpo JSON da requisição a partir dos campos da [CaptureSession].
     *
     * Usa [JSONObject] para serialização segura — escapa aspas, caracteres de controle
     * e outros caracteres especiais automaticamente, sem biblioteca de terceiros.
     *
     * O campo [CaptureSession.imageJpegBytes] é codificado em Base64 padrão (RFC 4648)
     * usando [Base64.getEncoder] — disponível a partir de API 26 (minSdk do SDK).
     *
     * As asserções incluem os campos de segurança da sessão para registro no backend:
     * - `location_suspicious`: flag de divergência GPS × NETWORK (Camada 3.5)
     * - `has_integrity_token`: indica se a Play Integrity API retornou token (Camada 2)
     */
    private fun buildRequestBody(session: CaptureSession): String {
        // Codifica a imagem JPEG em Base64 para transporte JSON — API 26+
        val imageBase64 = Base64.getEncoder().encodeToString(session.imageJpegBytes)

        // Asserções de segurança da sessão incluídas no registro do backend
        val assertionsJson = JSONObject().apply {
            put("location_suspicious",  session.locationSuspicious)
            put("has_integrity_token",  session.deviceIntegrityToken.isNotEmpty())
        }

        return JSONObject().apply {
            put("session_id",        session.sessionId)
            put("image_base64",      imageBase64)
            put("manifest_json",     session.manifestJson)
            put("frame_hash_hex",    session.frameHashHex)
            put("captured_at_ms",    session.capturedAtMs)
            put("assertions",        assertionsJson)
            // Key Attestation — incluído apenas quando disponível (fallback sem cloudProjectNumber)
            if (session.attestationChain != null) {
                val chainArray = org.json.JSONArray()
                session.attestationChain.forEach { chainArray.put(it) }
                put("attestation_chain", chainArray)
                put("attestation_type",  session.attestationType)
            }
        }.toString()
    }

    /**
     * Parseia o corpo JSON de uma resposta 2xx do backend.
     *
     * Campos esperados: `session_id`, `manifest_url`, `processed_at`.
     * Qualquer falha no parsing (campo ausente, JSON inválido) é tratada como erro
     * não-retryable — indica incompatibilidade de contrato com o backend.
     */
    private fun parseSuccess(body: String): BackendResult {
        return try {
            val json        = JSONObject(body)
            val sessionId   = json.getString("session_id")
            val manifestUrl = json.getString("manifest_url")
            val processedAt = json.getString("processed_at")
            BackendResult.Success(sessionId, manifestUrl, processedAt)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao parsear resposta de sucesso do backend: $body", e)
            BackendResult.Error(
                message     = "Resposta inválida do backend: ${e.message}",
                isRetryable = false
            )
        }
    }
}
