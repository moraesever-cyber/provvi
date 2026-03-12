package br.com.provvi.security

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
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

    // Play Integrity API não está disponível — PROVVI_CLOUD_PROJECT não configurado,
    // GMS ausente, ou warm-up não foi chamado antes da captura
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
    TIMEOUT               // Resposta não chegou dentro do limite configurado
}

// ---------------------------------------------------------------------------
// Verificador principal — Standard Play Integrity API
// ---------------------------------------------------------------------------

/**
 * Verifica a integridade do dispositivo utilizando a Standard Play Integrity API do Google.
 *
 * Utiliza a Standard API (disponível desde integrity:1.3.0) em vez da Classic API.
 * A Standard API separa o ciclo de vida em duas fases:
 *
 * - **Pré-aquecimento** ([warmUp]): realiza a conexão com os servidores do Google e prepara
 *   o token provider. Deve ser chamado o mais cedo possível, antes da captura.
 *   Operação de rede (~2–5s), executada uma única vez por sessão do app.
 *
 * - **Requisição do token** ([check]): usa o provider pré-aquecido para emitir um token
 *   vinculado ao hash da operação atual. Rápido (~100–300ms) quando pré-aquecido.
 *
 * **Arquitetura Provvi:**
 * A Provvi mantém um único projeto Google Cloud com a Play Integrity API habilitada.
 * O [PROVVI_CLOUD_PROJECT] é uma constante interna do SDK — integradores não precisam
 * configurar nada. O token gerado atesta o package name e o certificado de assinatura
 * do APK do integrador (verificado pelo Google Play Services).
 *
 * Se [PROVVI_CLOUD_PROJECT] for null (antes da conta corporativa ser criada), [check]
 * retorna [IntegrityResult.Unavailable] sem bloquear o pipeline de captura.
 */
class DeviceIntegrityChecker(private val context: Context) {

    // Timeout para o pré-aquecimento (operação de rede, feita uma vez por sessão do app)
    private val timeoutWarmUpMillis = 10_000L

    // Timeout para a requisição do token com provider já pré-aquecido
    private val timeoutTokenMillis = 3_000L

    // Provider pré-aquecido — null até warmUp() ser chamado com sucesso
    private var tokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    /** Retorna true se o provider já foi pré-aquecido e está pronto para emitir tokens. */
    fun isReady(): Boolean = tokenProvider != null

    /**
     * Pré-aquece o token provider da Standard Play Integrity API.
     *
     * Deve ser chamado o mais cedo possível — idealmente quando a tela inicial do app
     * carrega, antes do usuário acionar a captura. O pré-aquecimento ocorre em background
     * e não bloqueia a UI. Quando a captura for iniciada, o token estará pronto.
     *
     * Se [cloudProjectNumber] for 0 (não configurado), o warm-up é ignorado silenciosamente
     * e [check] retornará [IntegrityResult.Unavailable].
     *
     * @param cloudProjectNumber Número do projeto Google Cloud com a Play Integrity API habilitada.
     *                           Use o default de [ProvviConfig] para o projeto Provvi, ou passe
     *                           seu próprio número ao integrar o SDK com projeto próprio.
     */
    suspend fun warmUp(cloudProjectNumber: Long) {
        if (cloudProjectNumber <= 0L) return

        try {
            val manager = IntegrityManagerFactory.createStandard(context)
            val request = StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build()

            tokenProvider = withTimeoutOrNull(timeoutWarmUpMillis) {
                suspendCancellableCoroutine { continuation ->
                    manager.prepareIntegrityToken(request)
                        .addOnSuccessListener { provider ->
                            continuation.resume(provider)
                        }
                        .addOnFailureListener { _ ->
                            continuation.resume(null)
                        }
                }
            }
        } catch (e: Exception) {
            // GMS indisponível ou versão do Play Services incompatível — degradação silenciosa
            tokenProvider = null
        }
    }

    /**
     * Solicita um token de integridade vinculado ao hash da operação atual.
     *
     * Requer que [warmUp] tenha sido chamado previamente com [PROVVI_CLOUD_PROJECT] válido.
     * Se o provider não estiver pronto, retorna [IntegrityResult.Unavailable] imediatamente
     * sem bloquear o pipeline — a ausência do token é registrada no manifesto C2PA.
     *
     * @param requestHash SHA-256 ou identificador único da operação (ex.: sessionId).
     *                    Vincula o token a esta operação específica, impedindo reutilização.
     * @return [IntegrityResult] com o veredicto ou o motivo da indisponibilidade.
     */
    suspend fun check(requestHash: String): IntegrityResult {
        val provider = tokenProvider ?: return IntegrityResult.Unavailable

        val tokenResponse = withTimeoutOrNull(timeoutTokenMillis) {
            suspendCancellableCoroutine { continuation ->
                val request = StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build()

                provider.request(request)
                    .addOnSuccessListener { response ->
                        continuation.resume(Result.success(response.token()))
                    }
                    .addOnFailureListener { exception ->
                        continuation.resume(Result.failure(exception))
                    }
            }
        }

        if (tokenResponse == null) {
            return IntegrityResult.Failed(IntegrityFailReason.TIMEOUT)
        }

        val tokenBase64 = tokenResponse.getOrElse {
            return IntegrityResult.Failed(IntegrityFailReason.TOKEN_REQUEST_FAILED)
        }

        val verdict = decodeVerdictFromToken(tokenBase64)
        return IntegrityResult.Verified(verdict)
    }

    /**
     * Retorna um mapa com os campos de integridade prontos para inclusão no manifesto C2PA.
     */
    fun toManifestAssertion(verdict: DeviceVerdict): Map<String, Any> = mapOf(
        "meets_strong_integrity"  to verdict.meetsStrongIntegrity,
        "meets_device_integrity"  to verdict.meetsDeviceIntegrity,
        "meets_basic_integrity"   to verdict.meetsBasicIntegrity,
        "token_validated_backend" to true,
        "token_sha256_prefix"     to verdict.tokenBase64.take(16)
    )

    /**
     * Decodifica os campos de integridade do payload JWT sem verificar a assinatura.
     * A validação definitiva é responsabilidade do backend.
     */
    private fun decodeVerdictFromToken(tokenBase64: String): DeviceVerdict {
        return try {
            val parts = tokenBase64.split(".")
            val payloadJson = String(
                android.util.Base64.decode(
                    parts[1],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                )
            )

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
            DeviceVerdict(
                meetsStrongIntegrity = false,
                meetsDeviceIntegrity = false,
                meetsBasicIntegrity  = false,
                tokenBase64          = tokenBase64
            )
        }
    }
}
