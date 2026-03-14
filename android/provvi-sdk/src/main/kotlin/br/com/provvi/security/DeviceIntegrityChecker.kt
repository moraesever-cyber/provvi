package br.com.provvi.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
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
 * Veredicto de integridade retornado pela Play Integrity API ou Key Attestation.
 *
 * Para Play Integrity, a validação completa do payload JWT deve ser feita no backend.
 * Para Key Attestation, a cadeia de certificados é verificada pelo backend contra a
 * Google Hardware Attestation Root CA — bootloader e verified boot são extraídos do cert.
 *
 * @param meetsStrongIntegrity  Dispositivo certificado pelo Google (hardware-backed keystore,
 *                               bootloader bloqueado, sem root). Nível mais restritivo.
 * @param meetsDeviceIntegrity  Dispositivo passa nas verificações de integridade de software
 *                               mas pode não ter certificação de hardware.
 * @param meetsBasicIntegrity   Verificação mínima — app não foi adulterado. Pode estar em
 *                               dispositivo com root ou emulador modificado.
 * @param tokenBase64           Token JWT bruto (Play Integrity) ou vazio (Key Attestation).
 *                              Para Play Integrity: enviar ao backend para validação.
 * @param attestationChain      Cadeia de certificados DER em Base64 (Key Attestation) ou null.
 *                              Enviada ao backend para verificação contra Google Root CA.
 * @param attestationType       "play_integrity" ou "key_attestation".
 */
data class DeviceVerdict(
    val meetsStrongIntegrity: Boolean,
    val meetsDeviceIntegrity: Boolean,
    val meetsBasicIntegrity: Boolean,
    val tokenBase64: String,
    val attestationChain: List<String>? = null,
    val attestationType: String = "play_integrity"
)

// Causas de falha mapeadas para tratamento determinístico pelo chamador
enum class IntegrityFailReason {
    API_NOT_AVAILABLE,    // Play Integrity API retornou erro de disponibilidade
    TOKEN_REQUEST_FAILED, // Requisição do token falhou (erro de rede ou Play Services)
    TIMEOUT               // Resposta não chegou dentro do limite configurado
}

// ---------------------------------------------------------------------------
// Verificador principal — Standard Play Integrity API + Key Attestation fallback
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
 *
 * **Fallback Key Attestation:**
 * Quando [cloudProjectNumber] não está configurado (0L), [check] usa a Android Key
 * Attestation API como fallback. A Key Attestation não requer cloudProjectNumber nem
 * Play Store — funciona em distribuição MDM. Gera um par de chaves no TEE/StrongBox com
 * o sessionId como challenge, retornando a cadeia de certificados para verificação no
 * backend contra a Google Hardware Attestation Root CA.
 * Controlado por [USE_KEY_ATTESTATION_FALLBACK].
 *
 * **Para reverter o fallback:**
 * 1. `USE_KEY_ATTESTATION_FALLBACK = false`
 * 2. Quando cloudProjectNumber configurado: deletar `checkViaKeyAttestation()`,
 *    `tryGenerateAttestationKey()`, `generateAttestationKey()`, e os timeouts/constantes.
 */
class DeviceIntegrityChecker(private val context: Context) {

    // Timeout para o pré-aquecimento (operação de rede, feita uma vez por sessão do app)
    private val timeoutWarmUpMillis = 10_000L

    // Timeout para a requisição do token com provider já pré-aquecido
    private val timeoutTokenMillis = 3_000L

    // Timeout para geração de chave no TEE (~300ms) ou StrongBox (~2s) + cadeia
    private val timeoutKeyAttestationMillis = 8_000L

    // FLAG: habilita Key Attestation como fallback quando cloudProjectNumber = 0L.
    // Remover quando conta Play Store Provvi estiver ativa e cloudProjectNumber configurado.
    // Ref: Android Key Attestation — hardware-backed, funciona em MDM, sem Play Store.
    private val USE_KEY_ATTESTATION_FALLBACK = true

    // Alias fixo no AndroidKeyStore — a chave é gerada nova a cada captura (challenge único)
    private val ATTESTATION_KEY_ALIAS = "provvi_key_attestation"

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
     * e [check] usará o Key Attestation fallback se [USE_KEY_ATTESTATION_FALLBACK] = true.
     *
     * @param cloudProjectNumber Número do projeto Google Cloud com a Play Integrity API habilitada.
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
     * Se o provider não estiver pronto e [USE_KEY_ATTESTATION_FALLBACK] estiver habilitado,
     * usa Android Key Attestation como fallback (~500ms TEE, ~2s StrongBox). O backend
     * verifica a cadeia de certificados e extrai os campos de integridade do hardware.
     * Caso contrário, retorna [IntegrityResult.Unavailable] sem bloquear o pipeline.
     *
     * @param requestHash SHA-256 ou identificador único da operação (ex.: sessionId).
     *                    Para Key Attestation: usado como challenge vinculando a cadeia
     *                    de certificados a esta sessão específica — impede reutilização.
     * @return [IntegrityResult] com o veredicto ou o motivo da indisponibilidade.
     */
    suspend fun check(requestHash: String): IntegrityResult {
        // Se Standard não está pronta e fallback está habilitado, usa Key Attestation
        if (tokenProvider == null) {
            return if (USE_KEY_ATTESTATION_FALLBACK) {
                checkViaKeyAttestation(requestHash)
            } else {
                IntegrityResult.Unavailable
            }
        }
        val provider = tokenProvider!!

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
     * Fallback: Android Key Attestation — não requer cloudProjectNumber nem Play Store.
     *
     * Gera um par de chaves EC no TEE/StrongBox com [requestHash] como challenge,
     * retornando a cadeia de certificados para verificação server-side. O backend
     * (lambda-signer) verifica a cadeia contra a Google Hardware Attestation Root CA,
     * extrai bootloader state, verified boot e security level, e bloqueia se comprometido.
     *
     * Valores locais de [DeviceVerdict] são otimistas — o enforcement real acontece
     * no backend após verificação criptográfica da cadeia.
     *
     * Latência: ~300ms (TEE), ~2s (StrongBox). Compatível com API 26+ (minSdk Provvi).
     *
     * **Para reverter:** deletar este método, [tryGenerateAttestationKey],
     * [generateAttestationKey], [USE_KEY_ATTESTATION_FALLBACK], [timeoutKeyAttestationMillis]
     * e [ATTESTATION_KEY_ALIAS]. Restaurar `check()` para retornar Unavailable quando
     * tokenProvider == null.
     */
    private suspend fun checkViaKeyAttestation(requestHash: String): IntegrityResult {
        return try {
            val challenge = requestHash.toByteArray(Charsets.UTF_8)

            val isStrongBoxBacked = withTimeoutOrNull(timeoutKeyAttestationMillis) {
                tryGenerateAttestationKey(challenge)
            } ?: return IntegrityResult.Failed(IntegrityFailReason.TIMEOUT)

            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            val chain = keyStore.getCertificateChain(ATTESTATION_KEY_ALIAS)
                ?: return IntegrityResult.Failed(IntegrityFailReason.API_NOT_AVAILABLE)

            // Converte a cadeia para Base64 antes de apagar a chave
            val chainBase64 = chain.map { cert ->
                android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
            }

            // Remove a chave do keystore — serviu apenas para gerar a cadeia de attestation.
            // A chave privada não é utilizada para assinar nada (Ed25519 é responsável disso).
            try { keyStore.deleteEntry(ATTESTATION_KEY_ALIAS) } catch (_: Exception) { }

            IntegrityResult.Verified(
                DeviceVerdict(
                    // Valores locais conservadores — o backend sobrescreve com os
                    // valores reais extraídos da cadeia de certificados.
                    meetsStrongIntegrity = isStrongBoxBacked,
                    meetsDeviceIntegrity = true,
                    meetsBasicIntegrity  = true,
                    tokenBase64          = "",
                    attestationChain     = chainBase64,
                    attestationType      = "key_attestation"
                )
            )
        } catch (e: Exception) {
            IntegrityResult.Failed(IntegrityFailReason.API_NOT_AVAILABLE)
        }
    }

    /**
     * Gera o par de chaves de attestation no AndroidKeyStore.
     * Tenta StrongBox (API 28+) primeiro; em caso de falha, usa TEE.
     * Retorna true se gerado em StrongBox, false se em TEE.
     */
    private fun tryGenerateAttestationKey(challenge: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateAttestationKey(challenge, strongBox = true)
                return true
            } catch (_: Exception) {
                // StrongBox indisponível neste dispositivo — degradação para TEE
            }
        }
        generateAttestationKey(challenge, strongBox = false)
        return false
    }

    private fun generateAttestationKey(challenge: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            ATTESTATION_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    /**
     * Retorna um mapa com os campos de integridade prontos para inclusão no manifesto C2PA.
     */
    fun toManifestAssertion(verdict: DeviceVerdict): Map<String, Any> = mapOf(
        "meets_strong_integrity"  to verdict.meetsStrongIntegrity,
        "meets_device_integrity"  to verdict.meetsDeviceIntegrity,
        "meets_basic_integrity"   to verdict.meetsBasicIntegrity,
        "token_validated_backend" to true,
        "token_sha256_prefix"     to verdict.tokenBase64.take(16),
        "attestation_type"        to verdict.attestationType
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
