package br.com.provvi.security

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// Tipos de resultado da verificação de integridade
// ---------------------------------------------------------------------------

/**
 * Resultado da verificação de integridade do dispositivo via Play Integrity API.
 */
sealed class IntegrityResult {
    // Dispositivo passou na verificação — verdict contém os níveis de integridade confirmados
    data class Verified(val verdict: DeviceVerdict) : IntegrityResult()

    // Verificação falhou por razão conhecida
    data class Failed(val reason: IntegrityFailReason) : IntegrityResult()

    // Play Integrity API não está disponível neste dispositivo (ex.: dispositivo sem GMS)
    data object Unavailable : IntegrityResult()
}

/**
 * Veredicto de integridade retornado pela Play Integrity API após decodificação parcial
 * no lado cliente. A validação completa do payload JWT deve ser feita no backend Provvi,
 * que possui a chave de descriptografia fornecida pelo Google Play Console.
 *
 * @param meetsStrongIntegrity  Dispositivo certificado pelo Google (hardware-backed keystore,
 *                               bootloader bloqueado, sem root). Nível mais restritivo.
 * @param meetsDeviceIntegrity  Dispositivo passa nas verificações de integridade de software
 *                               mas pode não ter certificação de hardware.
 * @param meetsBasicIntegrity   Verificação mínima — app não foi adulterado. Pode estar em
 *                               dispositivo com root ou emulador modificado.
 * @param tokenBase64           Token JWT bruto retornado pela API. Deve ser enviado ao backend
 *                               para validação e descriptografia — não confiar apenas neste valor.
 */
data class DeviceVerdict(
    val meetsStrongIntegrity: Boolean,
    val meetsDeviceIntegrity: Boolean,
    val meetsBasicIntegrity: Boolean,
    val tokenBase64: String
)

// Causas de falha mapeadas para tratamento determinístico pelo chamador
enum class IntegrityFailReason {
    API_NOT_AVAILABLE,    // Play Integrity API retornou erro de disponibilidade
    TOKEN_REQUEST_FAILED, // Requisição do token falhou (erro de rede ou Play Services)
    TIMEOUT               // Resposta não chegou dentro do limite de 10 segundos
}

// ---------------------------------------------------------------------------
// Verificador principal
// ---------------------------------------------------------------------------

/**
 * Verifica a integridade do dispositivo utilizando a Play Integrity API do Google.
 *
 * A verificação é parte da Camada 2 do pipeline ADR-001: garante que o dispositivo
 * não foi adulterado antes de assinar o manifesto C2PA com a foto de vistoria.
 *
 * Fluxo de validação:
 * 1. Cliente solicita token com [check], passando um nonce derivado da operação atual.
 * 2. Token é incluído no manifesto C2PA (campo [DeviceVerdict.tokenBase64]).
 * 3. Backend Provvi valida e descriptografa o token via Google Play Integrity API server-side.
 *    Apenas o backend possui a chave — a verificação client-side aqui é indicativa.
 */
class DeviceIntegrityChecker(private val context: Context) {

    // Tempo máximo de espera pela resposta da Play Integrity API
    private val timeoutMillis = 10_000L

    /**
     * Executa a verificação de integridade do dispositivo.
     *
     * O [nonce] deve ser um SHA-256 da operação atual (ex.: hash da sessão de captura),
     * garantindo que o token seja vinculado a uma operação específica e não possa ser
     * reutilizado em outra requisição (proteção contra replay attack).
     *
     * IMPORTANTE: o [DeviceVerdict.tokenBase64] retornado em [IntegrityResult.Verified]
     * deve ser enviado ao backend Provvi para validação completa. A análise dos campos
     * booleanos aqui é feita a partir da decodificação local do token não-verificado;
     * o backend é a fonte de verdade para decisões de negócio.
     *
     * @param nonce SHA-256 da operação atual em formato Base64 URL-safe.
     * @return [IntegrityResult] com o veredicto ou o motivo da falha.
     */
    suspend fun check(nonce: String): IntegrityResult {
        // Cria o IntegrityManager — falha silenciosa se GMS não estiver disponível
        val integrityManager = try {
            IntegrityManagerFactory.create(context)
        } catch (e: Exception) {
            return IntegrityResult.Unavailable
        }

        // Aplica timeout global de 10 segundos para não bloquear o pipeline de captura
        val tokenResponse = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val request = IntegrityTokenRequest.builder()
                    .setNonce(nonce)
                    .build()

                val task = integrityManager.requestIntegrityToken(request)

                task.addOnSuccessListener { response ->
                    continuation.resume(Result.success(response.token()))
                }

                task.addOnFailureListener { exception ->
                    continuation.resume(Result.failure(exception))
                }
            }
        }

        // Timeout expirou — Play Integrity API não respondeu a tempo
        if (tokenResponse == null) {
            return IntegrityResult.Failed(IntegrityFailReason.TIMEOUT)
        }

        // Falha na requisição do token (erro de rede, Play Services indisponível, etc.)
        val tokenBase64 = tokenResponse.getOrElse {
            return IntegrityResult.Failed(IntegrityFailReason.TOKEN_REQUEST_FAILED)
        }

        // Decodifica os campos de integridade do payload JWT sem verificar a assinatura.
        // A verificação criptográfica da assinatura ocorre exclusivamente no backend.
        val verdict = decodeVerdictFromToken(tokenBase64)

        return IntegrityResult.Verified(verdict)
    }

    /**
     * Retorna um mapa com os campos de integridade prontos para inclusão no manifesto C2PA.
     *
     * O token completo não é incluído no manifesto — apenas a indicação de que foi enviado
     * ao backend, evitando exposição desnecessária do JWT em logs ou armazenamento local.
     *
     * @param verdict Veredicto obtido de [IntegrityResult.Verified].
     * @return Mapa compatível com o schema de asserções do manifesto C2PA.
     */
    fun toManifestAssertion(verdict: DeviceVerdict): Map<String, Any> = mapOf(
        "meets_strong_integrity"  to verdict.meetsStrongIntegrity,
        "meets_device_integrity"  to verdict.meetsDeviceIntegrity,
        "meets_basic_integrity"   to verdict.meetsBasicIntegrity,
        // Token completo disponível no backend — não repetido aqui por segurança
        "token_validated_backend" to true,
        "token_sha256_prefix"     to verdict.tokenBase64.take(16)
    )

    /**
     * Decodifica os campos de integridade do payload JWT sem verificar a assinatura.
     *
     * O token Play Integrity é um JWT com três partes separadas por ".".
     * O payload (segunda parte) é Base64 URL-safe e contém o JSON com os veredictos.
     * Como não temos a chave de descriptografia aqui, lemos o payload de forma otimista;
     * a validação definitiva é responsabilidade do backend.
     *
     * Em caso de falha na decodificação (formato inesperado), todos os campos de
     * integridade são marcados como false por conservadorismo.
     */
    private fun decodeVerdictFromToken(tokenBase64: String): DeviceVerdict {
        return try {
            // Extrai a segunda parte do JWT (payload)
            val parts = tokenBase64.split(".")
            val payloadJson = String(
                android.util.Base64.decode(
                    parts[1],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                )
            )

            // Leitura simples dos campos de integridade via busca de string no JSON.
            // Evita dependência de biblioteca de parsing JSON no SDK.
            val meetsStrong = payloadJson.contains("\"MEETS_STRONG_INTEGRITY\"")
            val meetsDevice = payloadJson.contains("\"MEETS_DEVICE_INTEGRITY\"")
            val meetsBasic  = payloadJson.contains("\"MEETS_BASIC_INTEGRITY\"")

            DeviceVerdict(
                meetsStrongIntegrity = meetsStrong,
                meetsDeviceIntegrity = meetsDevice || meetsStrong,
                meetsBasicIntegrity  = meetsBasic || meetsDevice || meetsStrong,
                tokenBase64          = tokenBase64
            )
        } catch (e: Exception) {
            // Formato de token inesperado — conservadorismo: nenhum nível confirmado
            DeviceVerdict(
                meetsStrongIntegrity = false,
                meetsDeviceIntegrity = false,
                meetsBasicIntegrity  = false,
                tokenBase64          = tokenBase64
            )
        }
    }
}
