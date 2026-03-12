package br.com.provvi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import br.com.provvi.assertions.GenericAssertions
import br.com.provvi.assertions.ProvviAssertions
import br.com.provvi.c2pa.C2paEngine
import br.com.provvi.c2pa.C2paResult
import br.com.provvi.camera.CaptureError
import br.com.provvi.camera.CaptureResult
import br.com.provvi.camera.SecureCameraCapture
import br.com.provvi.location.LocationResult
import br.com.provvi.location.LocationValidationOutcome
import br.com.provvi.location.LocationValidator
import br.com.provvi.backend.BackendErrorType
import br.com.provvi.backend.BackendResult
import br.com.provvi.backend.ProvviBackendClient
import br.com.provvi.recapture.RecaptureAnalysis
import br.com.provvi.recapture.RecaptureDetector
import br.com.provvi.recapture.RecaptureIndicator
import br.com.provvi.security.DeviceIntegrityChecker
import br.com.provvi.security.DeviceVerdict
import br.com.provvi.security.IntegrityResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID

// ---------------------------------------------------------------------------
// Configuração do SDK
// ---------------------------------------------------------------------------

// TODO: preencher após abertura de conta corporativa na Play Store.
//       Enquanto 0L, o pré-aquecimento da Play Integrity é ignorado e o campo
//       "Play Integrity" no manifesto C2PA ficará como "Indisponível".
private const val PROVVI_DEFAULT_CLOUD_PROJECT = 0L

/**
 * Configuração do Provvi SDK fornecida pelo integrador na inicialização.
 *
 * @param cloudProjectNumber Número do projeto Google Cloud com a Play Integrity API habilitada.
 *                           Padrão: projeto Provvi (será preenchido após abertura de conta).
 *                           Integradores com projeto Google Cloud próprio podem substituir.
 *
 *                           **Atenção (DT-020):** este mesmo valor deve ser usado ao configurar
 *                           [br.com.provvi.backend.BackendConfig.cloudProjectNumber] se o integrador
 *                           criar BackendConfig manualmente. Os dois campos precisam ser iguais.
 *
 * **Instancie [ProvviCapture] o mais cedo possível na Activity** (em `onCreate` ou `onResume`)
 * para que o pré-aquecimento da Play Integrity API complete antes da primeira captura.
 */
class ProvviConfig(
    val cloudProjectNumber: Long = PROVVI_DEFAULT_CLOUD_PROJECT
)

// ---------------------------------------------------------------------------
// Tipos públicos da sessão e resultado de captura
// ---------------------------------------------------------------------------

/**
 * Dados produzidos por uma sessão de captura autenticada pelo Provvi SDK.
 *
 * Todos os campos derivam de camadas distintas do pipeline ADR-001 e são
 * incluídos no manifesto C2PA para rastreabilidade completa da vistoria.
 *
 * @param sessionId             UUID único gerado no início da sessão.
 * @param manifestJson          Manifesto C2PA assinado serializado em JSON (Camada 4).
 * @param imageJpegBytes        Imagem JPEG produzida a partir do frame RAW da câmera.
 * @param frameHashHex          Hash SHA-256 do frame YUV antes da compressão (Camada 3).
 * @param locationSuspicious    true se divergência GPS × NETWORK > 500 m (Camada 3.5).
 * @param deviceIntegrityToken  Token JWT da Play Integrity API para validação no backend (Camada 2).
 *                              Vazio se a API não estava disponível.
 * @param capturedAtMs          Timestamp de captura em milissegundos desde Unix epoch
 *                              (System.currentTimeMillis() no momento do frame).
 * @param clockSuspicious       true se a deriva entre capturedAtMs e o relógio no momento
 *                              da consolidação for superior a 300 segundos — indica possível
 *                              manipulação de relógio ou frame stale.
 * @param manifestUrl           URL presigned S3 do manifesto após upload ao backend.
 *                              null se [ProvviBackendClient] não foi fornecido ou se o upload falhou.
 * @param integrityRisk         Nível de risco consolidado da sessão, incluído no manifesto C2PA.
 *                              Calculado como o máximo entre [deviceRisk] (Play Integrity) e
 *                              [recaptureRisk] (ADR-002). Ver DT-014.
 *                              "NONE"   — nenhum indicador de fraude detectado.
 *                              "MEDIUM" — dispositivo sem strongbox OU score de recaptura entre limiares.
 *                              "HIGH"   — dispositivo sem certificação de integridade OU reservado
 *                                         para bloqueios futuros com evidência registrada.
 */
data class CaptureSession(
    val sessionId: String,
    val manifestJson: String,
    val imageJpegBytes: ByteArray,
    val frameHashHex: String,
    val locationSuspicious: Boolean,
    val deviceIntegrityToken: String,
    val capturedAtMs: Long,
    val clockSuspicious: Boolean = false,
    val manifestUrl: String? = null,
    /** Tempo gasto em cada camada do pipeline, em milissegundos (DT-001). */
    val pipelineTimingsMs: Map<String, Long>? = null,
    val integrityRisk: String = "NONE"
) {
    // Gerado — necessário para data class com ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureSession) return false
        return sessionId == other.sessionId &&
               manifestJson == other.manifestJson &&
               imageJpegBytes.contentEquals(other.imageJpegBytes) &&
               frameHashHex == other.frameHashHex &&
               locationSuspicious == other.locationSuspicious &&
               deviceIntegrityToken == other.deviceIntegrityToken &&
               capturedAtMs == other.capturedAtMs &&
               clockSuspicious == other.clockSuspicious &&
               manifestUrl == other.manifestUrl &&
               pipelineTimingsMs == other.pipelineTimingsMs &&
               integrityRisk == other.integrityRisk
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + manifestJson.hashCode()
        result = 31 * result + imageJpegBytes.contentHashCode()
        result = 31 * result + frameHashHex.hashCode()
        result = 31 * result + locationSuspicious.hashCode()
        result = 31 * result + deviceIntegrityToken.hashCode()
        result = 31 * result + capturedAtMs.hashCode()
        result = 31 * result + clockSuspicious.hashCode()
        result = 31 * result + manifestUrl.hashCode()
        result = 31 * result + pipelineTimingsMs.hashCode()
        result = 31 * result + integrityRisk.hashCode()
        return result
    }
}

/**
 * Resultado de uma tentativa de captura autenticada.
 */
sealed class CaptureOutcome {
    // Captura concluída com sucesso — session contém todos os dados da vistoria
    data class Success(val session: CaptureSession) : CaptureOutcome()

    // Permissão de câmera ou localização negada pelo usuário
    data object PermissionDenied : CaptureOutcome()

    // Play Integrity API retornou dispositivo sem basic integrity — captura bloqueada
    data object DeviceCompromised : CaptureOutcome()

    // GPS reportou localização simulada — possível fraude em andamento
    data object MockLocationDetected : CaptureOutcome()

    // Análise de recaptura detectou artefatos de tela com score acima do limiar (ADR-002, Caminho 1)
    data class RecaptureSuspected(
        val score:      Float,
        val indicators: List<RecaptureIndicator>
    ) : CaptureOutcome()

    // Falha na assinatura C2PA do manifesto (Camada 4)
    data class SigningFailed(val reason: String) : CaptureOutcome()

    // Erro genérico no pipeline de câmera
    data class CaptureError(val reason: String) : CaptureOutcome()

    // Falha no upload ao backend com classificação de erro — TSA_UNAVAILABLE é o caso principal (DT-017)
    data class BackendError(
        val errorType: BackendErrorType,
        val message:   String
    ) : CaptureOutcome()
}

// ---------------------------------------------------------------------------
// Fachada pública do SDK
// ---------------------------------------------------------------------------

/**
 * Ponto de entrada único do Provvi SDK para integradores.
 *
 * Orquestra as 4 camadas do pipeline de captura autenticada definido no ADR-001:
 *
 * - **Camada 1** — Controle exclusivo da câmera via CameraX Camera2 interop
 * - **Camada 2** — Verificação de integridade do dispositivo (Play Integrity API)
 * - **Camada 3** — Hash SHA-256 do frame YUV antes da compressão JPEG
 * - **Camada 3.5** — Validação de localização multi-fonte com detecção de mock
 * - **Camada 4** — Assinatura C2PA do manifesto via c2pa-rs (Rust JNI)
 *
 * Não possui dependências de UI. O único vínculo com a camada de apresentação é
 * o [LifecycleOwner] passado a [capture], que controla a duração da sessão de câmera.
 *
 * ## Modelo de assinatura (três camadas)
 *
 * Todo manifesto com validade jurídica plena em produção deve conter:
 *
 * 1. **Ed25519** (este SDK) — prova de integridade de dispositivo.
 *    Chave gerada por aparelho, armazenada localmente, não nominativa.
 *    Prova que aquele hardware gerou aquele manifesto.
 *
 * 2. **TSA RFC 3161** (lambda-signer, ACT Certisign) — âncora temporal.
 *    Irrefutável, independente do relógio do dispositivo.
 *
 * 3. **ICP-Brasil A1** (lambda-signer, e-CNPJ Certisign) — autoria jurídica.
 *    Nominativa, com força probatória equivalente a assinatura manuscrita.
 *
 * Manifesto sem qualquer uma das três camadas → `verification_status: "incomplete"`
 * no lambda-verifier.
 */
@OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class ProvviCapture(
    context: Context,
    private val config: ProvviConfig = ProvviConfig()
) {

    private val appContext        = context.applicationContext
    private val integrityChecker  = DeviceIntegrityChecker(context)
    private val locationValidator = LocationValidator(context)
    private val cameraCapture     = SecureCameraCapture(context)
    private val c2paEngine        = C2paEngine()

    init {
        // Dispara o pré-aquecimento da Standard Play Integrity API imediatamente ao
        // criar o ProvviCapture — sem bloquear a inicialização. A operação de rede
        // (~2–5s) ocorre em background enquanto o usuário preenche os dados da vistoria.
        // Quando capture() for chamado, o token já estará pronto (~150ms).
        CoroutineScope(Dispatchers.IO).launch {
            if (!integrityChecker.isReady()) {
                integrityChecker.warmUp(config.cloudProjectNumber)
            }
        }
    }

    /**
     * Pré-aquece explicitamente o token provider da Standard Play Integrity API.
     *
     * O pré-aquecimento também é disparado automaticamente no `init` do [ProvviCapture].
     * Este método existe para integradores que precisam aguardar a conclusão antes de
     * prosseguir, ou para forçar nova tentativa em caso de falha silenciosa.
     *
     * Seguro para chamar múltiplas vezes — chamadas subsequentes são ignoradas se o
     * provider já estiver pronto.
     */
    suspend fun prepare() {
        if (integrityChecker.isReady()) return
        integrityChecker.warmUp(config.cloudProjectNumber)
    }

    /**
     * Executa o pipeline de captura autenticada e retorna uma [CaptureSession] assinada.
     *
     * A função é suspensa e segura para ser chamada de qualquer coroutine. Internamente,
     * cada camada gerencia seus próprios dispatchers (IO, Main conforme necessário).
     *
     * @param lifecycleOwner    Controla o tempo de vida da sessão de câmera.
     *                          A câmera é liberada assim que o primeiro frame é capturado.
     * @param assertions        Asserções do integrador incluídas no manifesto C2PA.
     *                          Use [GenericAssertions] para campos livres ou forneça uma
     *                          implementação específica do domínio (ex.: HabilitAiAssertions).
     * @return [CaptureOutcome] com a sessão assinada ou o motivo da falha.
     */
    suspend fun capture(
        lifecycleOwner: LifecycleOwner,
        assertions: ProvviAssertions = GenericAssertions(),
        backendClient: ProvviBackendClient? = null
    ): CaptureOutcome {

        // Identificador único da sessão — usado como nonce da Play Integrity API
        // para vincular o token ao contexto desta captura específica
        val sessionId = UUID.randomUUID().toString()

        // Marcador de tempo global do pipeline (DT-001)
        val totalStart = System.nanoTime()

        // -----------------------------------------------------------------
        // Camadas 2, 3.5 e 1+3 em paralelo
        //
        // Play Integrity e localização rodam em IO (chamadas de rede/sensor).
        // A câmera inicia na Main (CameraX exige Main para bindToLifecycle).
        // coroutineScope garante que todos os filhos concluam (ou falhem juntos)
        // antes de prosseguir — early-returns são tratados fora do escopo.
        // -----------------------------------------------------------------
        val tParallel = System.nanoTime()

        val parallelOutput = coroutineScope {
            val integrityDeferred = async(Dispatchers.Default) {
                val t = System.nanoTime()
                val result = integrityChecker.check(requestHash = sessionId)
                Pair(result, (System.nanoTime() - t) / 1_000_000L)
            }

            val locationDeferred = async(Dispatchers.IO) {
                val t = System.nanoTime()
                val result = locationValidator.validate()
                Pair(result, (System.nanoTime() - t) / 1_000_000L)
            }

            val frameDeferred = async(Dispatchers.Main) {
                val t = System.nanoTime()
                val channel = Channel<CaptureResult>(Channel.RENDEZVOUS)
                cameraCapture.startCapture(lifecycleOwner) { result ->
                    // Callback executa no analysisExecutor (thread não-coroutine).
                    // trySend é não-bloqueante: retorna falha silenciosa se nenhum
                    // receiver estiver aguardando (frames subsequentes ao primeiro).
                    channel.trySend(result)
                }
                // Aguarda o primeiro frame com timeout de 10 segundos
                val frame = withTimeoutOrNull(10_000L) { channel.receive() }
                Triple(frame, channel, (System.nanoTime() - t) / 1_000_000L)
            }

            Triple(integrityDeferred.await(), locationDeferred.await(), frameDeferred.await())
        }

        val (integrityResult, integrityCheckMs)     = parallelOutput.first
        val (locationOutcome, locationValidationMs) = parallelOutput.second
        val (firstFrame, frameChannel, cameraFrameMs) = parallelOutput.third

        val parallelMs = (System.nanoTime() - tParallel) / 1_000_000L

        // -----------------------------------------------------------------
        // Pós-processamento do resultado de integridade (Camada 2).
        // A câmera já iniciou em paralelo — cleanup necessário em caso de bloqueio.
        // -----------------------------------------------------------------
        var deviceVerdict: DeviceVerdict? = null
        var integrityToken = ""

        when (integrityResult) {
            is IntegrityResult.Verified -> {
                // meetsBasicIntegrity = false indica root, emulador modificado ou
                // manipulação de software — captura bloqueada conforme ADR-001
                if (!integrityResult.verdict.meetsBasicIntegrity) {
                    cameraCapture.stopCapture()
                    frameChannel.close()
                    return CaptureOutcome.DeviceCompromised
                }
                deviceVerdict  = integrityResult.verdict
                integrityToken = integrityResult.verdict.tokenBase64
            }
            is IntegrityResult.Failed,
            IntegrityResult.Unavailable -> {
                // Dispositivo sem GMS ou API indisponível: captura continua,
                // ausência de token é registrada no manifesto para decisão do backend
            }
        }

        // -----------------------------------------------------------------
        // Pós-processamento do resultado de localização (Camada 3.5).
        // Mock detectado → bloqueia captura e libera câmera já iniciada.
        // -----------------------------------------------------------------
        var locationResult: LocationResult? = null

        when (locationOutcome) {
            is LocationValidationOutcome.Valid        -> locationResult = locationOutcome.result
            is LocationValidationOutcome.MockDetected -> {
                cameraCapture.stopCapture()
                frameChannel.close()
                return CaptureOutcome.MockLocationDetected
            }
            LocationValidationOutcome.LocationUnavailable -> {
                // Nenhuma fonte respondeu: continua sem GPS; registrado no manifesto
            }
        }

        // -----------------------------------------------------------------
        // Pós-processamento do frame de câmera (Camadas 1+3).
        // -----------------------------------------------------------------
        if (firstFrame == null) {
            // Timeout: câmera não entregou nenhum frame no prazo esperado
            cameraCapture.stopCapture()
            frameChannel.close()
            return CaptureOutcome.CaptureError("timeout aguardando frame da câmera")
        }

        // O imageProxy deve ser processado ANTES de stopCapture() para garantir
        // que os buffers de pixel ainda sejam válidos (a sessão Camera2 ainda está aberta)
        if (firstFrame is CaptureResult.Error) {
            cameraCapture.stopCapture()
            frameChannel.close()
            return when (firstFrame.reason) {
                CaptureError.PERMISSION_DENIED -> CaptureOutcome.PermissionDenied
                CaptureError.NO_CAMERA        -> CaptureOutcome.CaptureError("câmera física não encontrada")
                CaptureError.CAMERA_IN_USE    -> CaptureOutcome.CaptureError("câmera em uso por outro processo")
            }
        }

        val successFrame = firstFrame as CaptureResult.Success

        // -----------------------------------------------------------------
        // Verificação de deriva de relógio.
        // Compara o timestamp registrado no frame (System.currentTimeMillis() no analyzer)
        // com o relógio atual. Deriva > 300 s sugere manipulação de relógio ou frame stale.
        // -----------------------------------------------------------------
        val clockCheckMs = System.currentTimeMillis()
        val clockDriftMs = Math.abs(clockCheckMs - successFrame.capturedAtMs)
        val clockSuspicious = clockDriftMs > 300_000L

        // -----------------------------------------------------------------
        // ADR-002 Caminho 1: Detecção de recaptura por artefatos físicos de tela.
        //
        // Executada ANTES de yuvToJpeg() enquanto os buffers YUV ainda são válidos
        // (a sessão Camera2 ainda está aberta). Captura suspeita é bloqueada aqui —
        // o frame não é convertido nem assinado, reduzindo superfície de ataque.
        // -----------------------------------------------------------------
        val t0Recapture = System.nanoTime()
        val recaptureAnalysis = withContext(Dispatchers.Default) {
            RecaptureDetector.analyze(successFrame.imageProxy)
        }
        val recaptureAnalysisMs = (System.nanoTime() - t0Recapture) / 1_000_000L

        // Sistema de dois limiares (ADR-002 v1.2):
        // - score > THRESHOLD_BLOCK  → bloqueia imediatamente (alta confiança)
        // - score > THRESHOLD_SUSPICIOUS → prossegue, manifesto flagado como MEDIUM
        //   A seguradora recebe a evidência completa e decide sobre o sinistro.
        if (recaptureAnalysis is RecaptureAnalysis.Suspicious &&
            recaptureAnalysis.score > RecaptureDetector.THRESHOLD_BLOCK) {
            successFrame.imageProxy.close()
            cameraCapture.stopCapture()
            frameChannel.close()
            return CaptureOutcome.RecaptureSuspected(
                score      = recaptureAnalysis.score,
                indicators = recaptureAnalysis.indicators
            )
        }

        // Risco de recaptura (nível base)
        val recaptureRisk: String = when {
            recaptureAnalysis is RecaptureAnalysis.Suspicious &&
            recaptureAnalysis.score > RecaptureDetector.THRESHOLD_BLOCK -> {
                // bloco já foi tratado acima — este ramo nunca é alcançado aqui
                "HIGH"
            }
            recaptureAnalysis is RecaptureAnalysis.Suspicious -> "MEDIUM"
            else -> "NONE"
        }

        // Risco de integridade do dispositivo (Play Integrity) — DT-014
        // Avalia apenas quando a API retornou veredicto — se Unavailable, não penaliza (DT-002)
        val deviceRisk: String = when {
            deviceVerdict == null                -> "NONE"   // API indisponível — sem penalidade
            !deviceVerdict.meetsDeviceIntegrity  -> "HIGH"   // sem certificação de hardware → alto risco
            !deviceVerdict.meetsBasicIntegrity   -> "HIGH"   // nunca alcançado (bloqueado em IntegrityResult.Verified)
            !deviceVerdict.meetsStrongIntegrity  -> "MEDIUM" // certificado mas sem strongbox
            else                                 -> "NONE"
        }

        // Risco consolidado: o mais severo entre os dois eixos
        val integrityRisk: String = when {
            recaptureRisk == "HIGH"   || deviceRisk == "HIGH"   -> "HIGH"
            recaptureRisk == "MEDIUM" || deviceRisk == "MEDIUM" -> "MEDIUM"
            else                                                 -> "NONE"
        }

        // Captura rotação do sensor antes de liberar o ImageProxy
        val rotationDegrees = successFrame.imageProxy.imageInfo.rotationDegrees

        // Converte o frame YUV_420_888 para JPEG antes de liberar a sessão de câmera
        val t0Jpeg = System.nanoTime()
        val jpegBytes = withContext(Dispatchers.Default) {
            yuvToJpeg(successFrame.imageProxy)
        }
        val jpegConversionMs = (System.nanoTime() - t0Jpeg) / 1_000_000L

        // Libera o buffer do frame de volta ao pool da câmera
        successFrame.imageProxy.close()

        // Encerra a sessão de câmera — exclusividade garantida, hardware liberado
        cameraCapture.stopCapture()
        frameChannel.close()

        // -----------------------------------------------------------------
        // Consolidação das asserções de todas as camadas em um único mapa.
        // Este mapa é serializado e incluído no manifesto C2PA como asserção
        // customizada "com.provvi.capture" (ver C2paEngine.kt / lib.rs).
        // -----------------------------------------------------------------
        val allAssertions = buildMap<String, Any> {
            put("session_id", sessionId)

            // Camada 3: hash do frame RAW (pré-codec)
            putAll(successFrame.frameHash.toManifestAssertion())

            // Timestamp de relógio de parede e flag de deriva
            put("captured_at_epoch_ms", successFrame.capturedAtMs)
            put("clock_suspicious", clockSuspicious)

            // Identificação do hardware de câmera para auditoria
            put("physical_camera_id", successFrame.physicalCameraId)

            // Camada 3.5: localização validada ou indicação de indisponibilidade
            if (locationResult != null) {
                putAll(locationValidator.toManifestAssertion(locationResult))
            } else {
                put("location_unavailable", true)
            }

            // Camada 2: veredicto de integridade ou indicação de indisponibilidade
            if (deviceVerdict != null) {
                putAll(integrityChecker.toManifestAssertion(deviceVerdict))
            } else {
                put("device_integrity_unavailable", true)
            }

            // ADR-002 Caminho 1: resultado da análise de recaptura.
            // Suspicious com score <= THRESHOLD_BLOCK prossegue aqui com integrityRisk=MEDIUM.
            putAll(RecaptureDetector.toManifestAssertion(recaptureAnalysis))
            put("integrity_risk", integrityRisk)

            // DT-014: campos granulares de risco para auditoria e decisão da seguradora
            put("meets_strong_integrity", deviceVerdict?.meetsStrongIntegrity ?: false)
            put("meets_device_integrity", deviceVerdict?.meetsDeviceIntegrity ?: false)
            put("meets_basic_integrity",  deviceVerdict?.meetsBasicIntegrity  ?: false)
            put("device_risk",            deviceRisk)
            put("recapture_risk",         recaptureRisk)

            // Asserções do integrador — convertidas via ProvviAssertions.toMap()
            putAll(assertions.toMap())

            // DT-001: timings das camadas anteriores à assinatura (c2pa + backend não disponíveis aqui)
            put("pipeline_timings_ms", mapOf(
                "parallel_total_ms"      to parallelMs,
                "integrity_check_ms"     to integrityCheckMs,
                "location_validation_ms" to locationValidationMs,
                "camera_frame_ms"        to cameraFrameMs,
                "recapture_analysis_ms"  to recaptureAnalysisMs,
                "jpeg_conversion_ms"     to jpegConversionMs
            ))
        }

        // -----------------------------------------------------------------
        // Camada 1 — Assinatura Ed25519 via c2pa-rs (JNI).
        // Prova que este hardware específico (device_id) gerou este manifesto.
        // A assinatura ICP-Brasil A1 (Camada 3) é adicionada pelo lambda-signer no backend.
        // -----------------------------------------------------------------
        val t0Signing = System.nanoTime()
        val jpegWithExif = withContext(Dispatchers.Default) {
            insertExifOrientation(jpegBytes, rotationDegrees)
        }
        val signingResult = c2paEngine.sign(jpegWithExif, allAssertions)

        val c2paSigningMs = (System.nanoTime() - t0Signing) / 1_000_000L

        if (signingResult is C2paResult.Error) {
            return CaptureOutcome.SigningFailed(signingResult.message)
        }

        val signed = signingResult as C2paResult.Success

        // Constrói a sessão local — válida independentemente do resultado do upload
        var session = CaptureSession(
            sessionId            = sessionId,
            manifestJson         = signed.manifestJson,
            imageJpegBytes       = jpegWithExif,
            frameHashHex         = successFrame.frameHash.rawHashHex,
            locationSuspicious   = locationResult?.locationSuspicious ?: false,
            deviceIntegrityToken = integrityToken,
            capturedAtMs         = successFrame.capturedAtMs,
            clockSuspicious      = clockSuspicious,
            manifestUrl          = null,
            integrityRisk        = integrityRisk
        )

        // -----------------------------------------------------------------
        // Upload opcional ao backend (S3 + DynamoDB via Lambda).
        //
        // Falhas de upload são logadas mas NÃO invalidam a sessão local —
        // o integrador recebe a sessão assinada com manifestUrl = null e
        // pode implementar retry próprio se necessário.
        // -----------------------------------------------------------------
        var backendUploadMs = 0L
        if (backendClient != null) {
            val t0Upload = System.nanoTime()
            when (val uploadResult = backendClient.upload(session)) {
                is BackendResult.Success -> {
                    // Enriquece a sessão com a URL presigned do S3
                    session = session.copy(manifestUrl = uploadResult.manifestUrl)
                }
                is BackendResult.Error -> {
                    // TSA indisponível → propaga como CaptureOutcome.BackendError (DT-017)
                    // O pipeline já completou (câmera, hash, assinatura), mas a âncora temporal
                    // não foi obtida — a sessão não é persistida no S3/DynamoDB.
                    if (uploadResult.errorType == BackendErrorType.TSA_UNAVAILABLE) {
                        return CaptureOutcome.BackendError(
                            errorType = BackendErrorType.TSA_UNAVAILABLE,
                            message   = uploadResult.message
                        )
                    }
                    // Outros erros de upload — sessão local permanece válida sem a URL
                    android.util.Log.w(
                        "ProvviCapture",
                        "Upload ao backend falhou (retryable=${uploadResult.isRetryable}): ${uploadResult.message}"
                    )
                }
            }
            backendUploadMs = (System.nanoTime() - t0Upload) / 1_000_000L
        }

        val totalMs = (System.nanoTime() - totalStart) / 1_000_000L

        // Mapa completo de timings incluído na sessão para observabilidade (DT-001)
        val timings = mapOf(
            "parallel_total_ms"      to parallelMs,
            "integrity_check_ms"     to integrityCheckMs,
            "location_validation_ms" to locationValidationMs,
            "camera_frame_ms"        to cameraFrameMs,
            "recapture_analysis_ms"  to recaptureAnalysisMs,
            "jpeg_conversion_ms"     to jpegConversionMs,
            "c2pa_signing_ms"        to c2paSigningMs,
            "backend_upload_ms"      to backendUploadMs,
            "total_ms"               to totalMs
        )
        session = session.copy(pipelineTimingsMs = timings)

        android.util.Log.i("ProvviPerf", buildString {
            append("=== Pipeline Timings ===\n")
            timings.forEach { (k, v) -> append("  $k: ${v}ms\n") }
        })

        return CaptureOutcome.Success(session)
    }

    /**
     * Versão bloqueante de [capture] para uso por chamadores não-coroutine
     * (Java, C#/MAUI via JNI, scripts de teste).
     *
     * Bloqueia a thread chamadora até o pipeline completar.
     * NÃO chame da Main thread — use uma thread de background.
     *
     * **Contrato JNI estável — não alterar a assinatura.**
     *
     * @param lifecycleOwner Lifecycle a ser usado pelo CameraX.
     * @param assertions     Asserções de negócio do integrador.
     * @param backendClient  Cliente de upload opcional.
     * @return [CaptureOutcome] com o resultado do pipeline.
     */
    @JvmOverloads
    fun captureBlocking(
        lifecycleOwner: LifecycleOwner,
        assertions:     ProvviAssertions,
        backendClient:  ProvviBackendClient? = null
    ): CaptureOutcome = runBlocking {
        capture(lifecycleOwner, assertions, backendClient)
    }

    /**
     * Versão padronizada de [capture] que retorna [ProvviResult] em vez de lançar exceções (DT-019).
     *
     * Todos os casos de falha são mapeados para [ProvviResult.Failure] com [ProvviError] classificado.
     * Use este método em código novo. [capture] permanece disponível para compatibilidade.
     *
     * @param lifecycleOwner    Lifecycle a ser usado pelo CameraX.
     * @param assertions        Asserções de negócio do integrador.
     * @param backendClient     Cliente de upload opcional.
     * @return [ProvviResult]<[CaptureSession]> — sucesso ou falha classificada.
     */
    suspend fun captureResult(
        lifecycleOwner: LifecycleOwner,
        assertions:     ProvviAssertions = GenericAssertions(),
        backendClient:  ProvviBackendClient? = null
    ): ProvviResult<CaptureSession> {
        // DT-015: captura requer conexão ativa — verificar antes de iniciar câmera
        if (!isNetworkAvailable()) {
            return ProvviResult.Failure(
                ProvviError(ProvviErrorType.NETWORK_UNAVAILABLE,
                    "Sem conexão de rede — a captura requer conexão ativa")
            )
        }
        return try {
        when (val outcome = capture(lifecycleOwner, assertions, backendClient)) {
            is CaptureOutcome.Success -> ProvviResult.Success(outcome.session)

            CaptureOutcome.PermissionDenied -> ProvviResult.Failure(
                ProvviError(ProvviErrorType.PERMISSION_DENIED,
                    "Permissão de câmera ou localização negada pelo usuário")
            )
            CaptureOutcome.DeviceCompromised -> ProvviResult.Failure(
                ProvviError(ProvviErrorType.DEVICE_COMPROMISED,
                    "Dispositivo não atende ao requisito de integridade básica (basicIntegrity = false)")
            )
            CaptureOutcome.MockLocationDetected -> ProvviResult.Failure(
                ProvviError(ProvviErrorType.MOCK_LOCATION_DETECTED,
                    "Localização simulada detectada — desative apps de GPS falso")
            )
            is CaptureOutcome.RecaptureSuspected -> ProvviResult.Failure(
                ProvviError(ProvviErrorType.RECAPTURE_SUSPECTED,
                    "Score de recaptura ${outcome.score} acima do limiar — fotografe o objeto diretamente")
            )
            is CaptureOutcome.SigningFailed -> ProvviResult.Failure(
                ProvviError(ProvviErrorType.SIGNING_FAILED, outcome.reason)
            )
            is CaptureOutcome.CaptureError -> {
                // Strings de erro definidas em capture() — alterar lá requer atualizar este when.
                // TODO: considerar CaptureOutcome.CaptureError(reason: CameraFailReason) em versão futura.
                val type = when (outcome.reason) {
                    "timeout aguardando frame da câmera" -> ProvviErrorType.CAMERA_TIMEOUT
                    "câmera física não encontrada"       -> ProvviErrorType.CAMERA_NOT_FOUND
                    "câmera em uso por outro processo"   -> ProvviErrorType.CAMERA_IN_USE
                    else                                 -> ProvviErrorType.CAMERA_ERROR
                }
                ProvviResult.Failure(ProvviError(type, outcome.reason))
            }
            is CaptureOutcome.BackendError -> {
                // DT-017: mapeia erros de backend classificados para ProvviErrorType
                val type = when (outcome.errorType) {
                    BackendErrorType.TSA_UNAVAILABLE -> ProvviErrorType.TSA_UNAVAILABLE
                    BackendErrorType.AUTH_FAILED     -> ProvviErrorType.BACKEND_AUTH_FAILED
                    BackendErrorType.GENERIC         -> ProvviErrorType.BACKEND_UNAVAILABLE
                }
                ProvviResult.Failure(ProvviError(type, outcome.message))
            }
        }
    } catch (e: Exception) {
        ProvviResult.Failure(ProvviError(ProvviErrorType.UNKNOWN,
            "Erro inesperado no pipeline de captura: ${e.message}", e))
    }
    }

    /**
     * Versão bloqueante de [captureResult] para uso por chamadores não-coroutine (DT-019).
     *
     * Retorna [ProvviResult] em vez de lançar exceções.
     * NÃO chame da Main thread — use uma thread de background.
     *
     * @param lifecycleOwner Lifecycle a ser usado pelo CameraX.
     * @param assertions     Asserções de negócio do integrador.
     * @param backendClient  Cliente de upload opcional.
     * @return [ProvviResult]<[CaptureSession]> — sucesso ou falha classificada.
     */
    @JvmOverloads
    fun captureResultBlocking(
        lifecycleOwner: LifecycleOwner,
        assertions:     ProvviAssertions,
        backendClient:  ProvviBackendClient? = null
    ): ProvviResult<CaptureSession> = runBlocking {
        captureResult(lifecycleOwner, assertions, backendClient)
    }

    /**
     * Verifica se há conexão de rede com capacidade de internet.
     *
     * Usado por [captureResult] para bloquear antecipadamente capturas sem rede (DT-015).
     * Não é verificado por [capture] ou [captureBlocking] — eles continuam funcionando
     * offline, com upload falhando graciosamente em [BackendResult.Error].
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Libera todos os recursos de câmera mantidos pelo SDK.
     *
     * Deve ser chamado quando o componente de UI que possui o [LifecycleOwner]
     * for destruído, ou quando o integrador encerrar a sessão de vistoria.
     * Idempotente — seguro para chamar múltiplas vezes.
     */
    fun release() {
        cameraCapture.stopCapture()
    }

    // ---------------------------------------------------------------------------
    // Funções auxiliares privadas
    // ---------------------------------------------------------------------------

    /**
     * Converte um frame YUV_420_888 do CameraX para bytes JPEG em memória.
     *
     * O formato YUV_420_888 usa três planos separados (Y, U, V) com possíveis
     * strides de linha e pixel. O [YuvImage] do Android espera NV21 (Y + VU intercalado),
     * portanto os planos são remontados antes da compressão.
     *
     * A leitura é feita plano a plano descartando o padding de linha via [rowStride],
     * e o intercalamento VU respeita o [pixelStride] de cada plano — necessário em
     * dispositivos que usam NV12 (UV intercalado) em vez de planos separados.
     *
     * @param imageProxy Frame YUV ainda aberto. Não fechado aqui — responsabilidade do chamador.
     * @param quality    Qualidade JPEG de 0 a 100. Padrão 95 preserva detalhes para vistorias.
     * @return Bytes JPEG prontos para assinatura e armazenamento.
     */
    private fun yuvToJpeg(imageProxy: ImageProxy, quality: Int = 95): ByteArray {
        val width  = imageProxy.width
        val height = imageProxy.height

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        // Extrai o plano Y linha por linha descartando o padding de alinhamento de memória
        val yData   = ByteArray(width * height)
        val yBuffer = yPlane.buffer
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            yBuffer.get(yData, row * width, width)
        }

        // Monta o plano UV intercalado no formato NV21 (V antes de U).
        // Usa get(índice absoluto) para respeitar o pixelStride de cada plano
        // sem alterar a posição do buffer entre iterações.
        val halfWidth  = width / 2
        val halfHeight = height / 2
        val uvData     = ByteArray(width * height / 2)
        val uBuffer    = uPlane.buffer
        val vBuffer    = vPlane.buffer

        for (row in 0 until halfHeight) {
            for (col in 0 until halfWidth) {
                val uvIndex = row * width + col * 2
                uvData[uvIndex]     = vBuffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                uvData[uvIndex + 1] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }

        // Concatena Y + VU e comprime para JPEG usando a API nativa do Android
        val nv21         = yData + uvData
        val yuvImage     = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Insere o tag EXIF Orientation no JPEG gerado pelo sensor.
     *
     * CameraX não grava EXIF nos bytes retornados por yuvToJpeg() — o campo
     * imageInfo.rotationDegrees indica quantos graus o frame deve ser rotacionado
     * para ficar "de pé". Sem esse tag, visualizadores que respeitam EXIF (browsers,
     * S3 Object Viewer, iOS Photos) exibem a foto rotacionada 90°.
     *
     * A assinatura C2PA cobre jpegWithExif (objeto real armazenado no S3).
     * frameHashHex continua sendo SHA-256 do buffer YUV bruto — não é afetado.
     */
    private fun insertExifOrientation(jpegBytes: ByteArray, rotationDegrees: Int): ByteArray {
        val exifOrientation = when (rotationDegrees) {
            0   -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            90  -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED
        }
        if (exifOrientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL ||
            exifOrientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED) {
            return jpegBytes
        }
        return try {
            insertExifViaRewrite(jpegBytes, exifOrientation)
        } catch (e: Exception) {
            android.util.Log.w("ProvviCapture", "insertExifOrientation falhou: ${e.message} — retornando JPEG original")
            jpegBytes
        }
    }

    /**
     * Reescreve o JPEG inserindo um segmento APP1 com EXIF Orientation.
     *
     * Estrutura: SOI (2 bytes) + APP1 (marker + length + "Exif\0\0" + TIFF IFD) + resto do JPEG.
     * Qualquer APP1 preexistente no JPEG de origem é preservado logo após o novo.
     */
    private fun insertExifViaRewrite(jpegBytes: ByteArray, exifOrientation: Int): ByteArray {
        val tiffData    = buildExifOrientationBlock(exifOrientation)
        val exifHeader  = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"
        val app1Content = exifHeader + tiffData
        val app1Length  = app1Content.size + 2
        val app1Marker  = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            ((app1Length shr 8) and 0xFF).toByte(),
            (app1Length and 0xFF).toByte()
        )
        val app1Segment = app1Marker + app1Content
        val soi  = jpegBytes.copyOfRange(0, 2)
        val rest = jpegBytes.copyOfRange(2, jpegBytes.size)
        return soi + app1Segment + rest
    }

    /**
     * Constrói um bloco TIFF little-endian com um único IFD contendo apenas
     * o tag 0x0112 (Orientation).
     *
     * Estrutura TIFF:
     *   - Header (8 bytes): "II" + magic 0x002A + offset do IFD (0x00000008)
     *   - IFD entry count (2 bytes): 1
     *   - IFD entry (12 bytes): tag + type SHORT + count 1 + value
     *   - Next IFD offset (4 bytes): 0 (fim)
     */
    private fun buildExifOrientationBlock(orientation: Int): ByteArray {
        val header = byteArrayOf(
            0x49, 0x49,             // "II" — little-endian
            0x2A, 0x00,             // TIFF magic
            0x08, 0x00, 0x00, 0x00  // Offset do primeiro IFD = 8
        )
        val entryCount = byteArrayOf(0x01, 0x00)
        val orientValue = orientation and 0xFFFF
        val ifdEntry = byteArrayOf(
            0x12, 0x01,             // Tag 0x0112 (Orientation)
            0x03, 0x00,             // Type SHORT
            0x01, 0x00, 0x00, 0x00, // Count = 1
            (orientValue and 0xFF).toByte(),
            ((orientValue shr 8) and 0xFF).toByte(),
            0x00, 0x00
        )
        val nextIfd = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        return header + entryCount + ifdEntry + nextIfd
    }
}
