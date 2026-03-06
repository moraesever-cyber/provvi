package br.com.provvi

import android.content.Context
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
import br.com.provvi.backend.BackendResult
import br.com.provvi.backend.ProvviBackendClient
import br.com.provvi.recapture.RecaptureAnalysis
import br.com.provvi.recapture.RecaptureDetector
import br.com.provvi.recapture.RecaptureIndicator
import br.com.provvi.security.DeviceIntegrityChecker
import br.com.provvi.security.DeviceVerdict
import br.com.provvi.security.IntegrityResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID

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
 * @param capturedAtNanos       Timestamp de captura em nanosegundos, origem: sensor da câmera.
 * @param manifestUrl           URL presigned S3 do manifesto após upload ao backend.
 *                              null se [ProvviBackendClient] não foi fornecido ou se o upload falhou.
 */
data class CaptureSession(
    val sessionId: String,
    val manifestJson: String,
    val imageJpegBytes: ByteArray,
    val frameHashHex: String,
    val locationSuspicious: Boolean,
    val deviceIntegrityToken: String,
    val capturedAtNanos: Long,
    val manifestUrl: String? = null
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
               capturedAtNanos == other.capturedAtNanos &&
               manifestUrl == other.manifestUrl
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + manifestJson.hashCode()
        result = 31 * result + imageJpegBytes.contentHashCode()
        result = 31 * result + frameHashHex.hashCode()
        result = 31 * result + locationSuspicious.hashCode()
        result = 31 * result + deviceIntegrityToken.hashCode()
        result = 31 * result + capturedAtNanos.hashCode()
        result = 31 * result + manifestUrl.hashCode()
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
 */
@OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class ProvviCapture(context: Context) {

    private val integrityChecker  = DeviceIntegrityChecker(context)
    private val locationValidator = LocationValidator(context)
    private val cameraCapture     = SecureCameraCapture(context)
    private val c2paEngine        = C2paEngine()

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

        // -----------------------------------------------------------------
        // Camada 2: Integridade do dispositivo (Play Integrity API)
        // Executa antes da câmera para bloquear dispositivos comprometidos
        // o mais cedo possível no pipeline.
        // -----------------------------------------------------------------
        var deviceVerdict: DeviceVerdict? = null
        var integrityToken = ""

        when (val integrityResult = integrityChecker.check(nonce = sessionId)) {
            is IntegrityResult.Verified -> {
                // meetsBasicIntegrity = false indica root, emulador modificado ou
                // manipulação de software — captura bloqueada conforme ADR-001
                if (!integrityResult.verdict.meetsBasicIntegrity) {
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
        // Camada 3.5: Validação de localização multi-fonte
        // GPS + NETWORK; divergência > 500 m → locationSuspicious = true.
        // Mock detectado → captura bloqueada.
        // -----------------------------------------------------------------
        var locationResult: LocationResult? = null

        when (val locationOutcome = locationValidator.validate()) {
            is LocationValidationOutcome.Valid        -> locationResult = locationOutcome.result
            is LocationValidationOutcome.MockDetected -> return CaptureOutcome.MockLocationDetected
            LocationValidationOutcome.LocationUnavailable -> {
                // Nenhuma fonte respondeu: continua sem GPS; registrado no manifesto
            }
        }

        // -----------------------------------------------------------------
        // Camadas 1 + 3: Câmera exclusiva + hash pré-codec
        //
        // Canal RENDEZVOUS: entrega direta do primeiro frame para esta coroutine.
        // trySend() só tem sucesso quando receive() está aguardando — garante que
        // apenas o primeiro frame seja consumido e os subsequentes descartados.
        // -----------------------------------------------------------------
        val frameChannel = Channel<CaptureResult>(Channel.RENDEZVOUS)

        cameraCapture.startCapture(lifecycleOwner) { result ->
            // Callback executa no analysisExecutor (thread não-coroutine).
            // trySend é não-bloqueante: retorna falha silenciosa se nenhum
            // receiver estiver aguardando (frames subsequentes ao primeiro).
            frameChannel.trySend(result)
        }

        // Aguarda o primeiro frame com timeout de 10 segundos
        val firstFrame = withTimeoutOrNull(10_000L) { frameChannel.receive() }

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
        // ADR-002 Caminho 1: Detecção de recaptura por artefatos físicos de tela.
        //
        // Executada ANTES de yuvToJpeg() enquanto os buffers YUV ainda são válidos
        // (a sessão Camera2 ainda está aberta). Captura suspeita é bloqueada aqui —
        // o frame não é convertido nem assinado, reduzindo superfície de ataque.
        // -----------------------------------------------------------------
        val recaptureAnalysis = RecaptureDetector.analyze(successFrame.imageProxy)

        if (recaptureAnalysis is RecaptureAnalysis.Suspicious) {
            // Libera recursos antes de retornar — mesma sequência do caminho de erro normal
            successFrame.imageProxy.close()
            cameraCapture.stopCapture()
            frameChannel.close()
            return CaptureOutcome.RecaptureSuspected(
                score      = recaptureAnalysis.score,
                indicators = recaptureAnalysis.indicators
            )
        }

        // Converte o frame YUV_420_888 para JPEG antes de liberar a sessão de câmera
        val jpegBytes = yuvToJpeg(successFrame.imageProxy)

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

            // ADR-002 Caminho 1: resultado da análise de recaptura (Clean ou Inconclusive aqui,
            // pois Suspicious já retornou mais cedo no pipeline)
            putAll(RecaptureDetector.toManifestAssertion(recaptureAnalysis))

            // Asserções do integrador — convertidas via ProvviAssertions.toMap()
            putAll(assertions.toMap())
        }

        // -----------------------------------------------------------------
        // Camada 4: Assinatura C2PA via c2pa-rs (Rust JNI)
        // O manifesto é assinado com Ed25519 (dev) ou ICP-Brasil (produção).
        // -----------------------------------------------------------------
        val signingResult = c2paEngine.sign(jpegBytes, allAssertions)

        if (signingResult is C2paResult.Error) {
            return CaptureOutcome.SigningFailed(signingResult.message)
        }

        val signed = signingResult as C2paResult.Success

        // Constrói a sessão local — válida independentemente do resultado do upload
        var session = CaptureSession(
            sessionId            = sessionId,
            manifestJson         = signed.manifestJson,
            imageJpegBytes       = jpegBytes,
            frameHashHex         = successFrame.frameHash.rawHashHex,
            locationSuspicious   = locationResult?.locationSuspicious ?: false,
            deviceIntegrityToken = integrityToken,
            capturedAtNanos      = successFrame.frameHash.timestampNanos,
            manifestUrl          = null
        )

        // -----------------------------------------------------------------
        // Upload opcional ao backend (S3 + DynamoDB via Lambda).
        //
        // Falhas de upload são logadas mas NÃO invalidam a sessão local —
        // o integrador recebe a sessão assinada com manifestUrl = null e
        // pode implementar retry próprio se necessário.
        // -----------------------------------------------------------------
        if (backendClient != null) {
            when (val uploadResult = backendClient.upload(session)) {
                is BackendResult.Success -> {
                    // Enriquece a sessão com a URL presigned do S3
                    session = session.copy(manifestUrl = uploadResult.manifestUrl)
                }
                is BackendResult.Error -> {
                    // Upload falhou — sessão local permanece válida sem a URL
                    android.util.Log.w(
                        "ProvviCapture",
                        "Upload ao backend falhou (retryable=${uploadResult.isRetryable}): ${uploadResult.message}"
                    )
                }
            }
        }

        return CaptureOutcome.Success(session)
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
}
