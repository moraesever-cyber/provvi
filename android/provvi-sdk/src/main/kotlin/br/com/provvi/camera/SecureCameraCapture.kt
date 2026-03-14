package br.com.provvi.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import br.com.provvi.crypto.FrameHash
import br.com.provvi.crypto.FrameHasher
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

// Resultado de cada frame capturado ou de uma falha durante a sessão de captura
sealed class CaptureResult {
    // Frame RAW entregue com sucesso — o chamador é responsável por fechar o imageProxy
    data class Success(
        val imageProxy: ImageProxy,
        val frameHash: FrameHash,
        val physicalCameraId: String,
        val capturedAtMs: Long
    ) : CaptureResult()
    data class Error(val reason: CaptureError) : CaptureResult()
}

// Enum com as causas possíveis de falha de captura
enum class CaptureError {
    NO_CAMERA,          // Dispositivo não possui câmera física
    CAMERA_IN_USE,      // Câmera já está sendo usada por outro processo
    PERMISSION_DENIED   // Permissão android.permission.CAMERA não foi concedida
}

/**
 * Gerencia o acesso exclusivo à câmera utilizando CameraX com interoperabilidade Camera2.
 *
 * Não possui dependências de UI (sem Activity, Fragment ou View).
 * Deve ser instanciado pela aplicação host e ter seu ciclo de vida controlado externamente.
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class SecureCameraCapture(private val context: Context) {

    // Executor dedicado para análise de frames, isolado do thread principal.
    // Declarado como var para permitir recriação após shutdown entre capturas.
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Referências mantidas para liberar recursos corretamente no stopCapture()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    /**
     * Verifica se o dispositivo possui câmera física.
     * Protege contra emuladores ou dispositivos sem hardware de câmera.
     */
    private fun hasPhysicalCamera(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * Verifica se a permissão de câmera foi concedida pelo usuário.
     */
    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Obtém o ProcessCameraProvider de forma suspensa, sem bloquear a thread principal.
     * Usa suspendCancellableCoroutine para integrar o callback do ListenableFuture
     * com o modelo de coroutines.
     */
    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    // Retoma a coroutine com o provider assim que o sistema o disponibilizar
                    continuation.resume(future.get())
                },
                ContextCompat.getMainExecutor(context)
            )
            // Cancela o future caso a coroutine pai seja cancelada antes da conclusão
            continuation.invokeOnCancellation { future.cancel(true) }
        }

    /**
     * Aplica configurações determinísticas de captura via Camera2 interop.
     *
     * O objetivo é minimizar o processamento de imagem feito pelo OEM/sistema para
     * garantir que:
     * - O RecaptureDetector receba sinais espectrais consistentes entre dispositivos
     * - Padrões de subpixel de telas sejam preservados (não suavizados por HDR/NR)
     * - A imagem final seja tecnicamente adequada para vistoria de seguros
     *
     * O que é controlado:
     * - HDR_MODE: desativado — evita fusão de frames e tone mapping agressivo
     * - NOISE_REDUCTION_MODE: MINIMAL — preserva textura real sem destruir subpixels
     * - EDGE_MODE: OFF — sem sharpening artificial que afeta análise espectral
     * - ABERRATION_MODE: FAST — correção de aberração cromática básica mantida
     * - ANTIBANDING_MODE: AUTO — reduz flickering de telas sem afetar análise
     *
     * O que NÃO é controlado (deixado para o auto do sistema):
     * - Exposição e ISO — ajuste automático é necessário para qualidade em campo
     * - White balance — auto é preferível para fidelidade de cor em vistoria
     * - Foco — autofoco contínuo é necessário para usabilidade do operador
     *
     * Chamado após bindToLifecycle(), quando camera2Control está disponível.
     *
     * @param camera2Control Controle Camera2 obtido via interop após o bind.
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun applyProvviCaptureSettings(camera2Control: Camera2CameraControl) {
        try {
            val options = CaptureRequestOptions.Builder()

                // Desativa HDR de captura (fusão de múltiplos frames)
                // Sem isso, frames são fundidos com tone mapping que destrói micro-padrões UV
                // CONTROL_SCENE_MODE_HDR não é suportado em todos os dispositivos —
                // tentativa silenciosa, sem crash se o dispositivo não suportar
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CameraMetadata.CONTROL_SCENE_MODE_DISABLED
                )

                // Noise reduction mínima — preserva textura e variância cromática real
                // MINIMAL aplica apenas correção de hot pixels, sem suavização de área
                // OFF poderia introduzir ruído excessivo; MINIMAL é o balanço correto
                .setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                )

                // Sem sharpening de borda — edge enhancement artificial
                // afeta o score_edge do RecaptureDetector e cria artefatos espectrais
                .setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CameraMetadata.EDGE_MODE_OFF
                )

                // Correção de aberração cromática básica mantida (FAST)
                // Elimina fringing de lente sem afetar padrões de subpixel de tela
                // OFF criaria artefatos de cor nas bordas que prejudicariam a análise
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST
                )

                // Antibanding automático — reduz flickering de telas fotografadas
                // sem interferir na análise cromática
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                )

                .build()

            camera2Control.captureRequestOptions = options

            Log.d("CameraCapture", "Provvi capture settings applied: HDR=off, NR=minimal, edge=off")

        } catch (e: Exception) {
            // Falha silenciosa — dispositivo pode não suportar alguma das opções
            // A captura continua com as configurações padrão do sistema
            // Não bloquear a captura por configurações opcionais de otimização
            Log.w("CameraCapture", "Não foi possível aplicar todas as configurações Provvi: ${e.message}")
        }
    }

    /**
     * Inicia a captura exclusiva de frames RAW da câmera traseira.
     *
     * Realiza validações de hardware e permissão antes de abrir a câmera.
     * Utiliza [ImageAnalysis] com estratégia STRATEGY_KEEP_ONLY_LATEST para garantir
     * que apenas o frame mais recente seja processado, evitando acúmulo de memória.
     *
     * O [onFrameCaptured] é invocado para cada frame disponível com [CaptureResult.Success],
     * ou com [CaptureResult.Error] em caso de falha durante a sessão.
     * Após processar um [CaptureResult.Success], o chamador DEVE invocar
     * [ImageProxy.close] para liberar o buffer do frame.
     *
     * As configurações de câmera são fixadas via [applyProvviCaptureSettings] para
     * garantir captura determinística: HDR desativado, noise reduction mínima,
     * sem edge enhancement. Isso garante consistência espectral entre dispositivos
     * e preserva os sinais utilizados pelo [RecaptureDetector].
     *
     * @param lifecycleOwner Dono do ciclo de vida — a câmera segue seu estado (STARTED/STOPPED).
     * @param onFrameCaptured Callback invocado com o resultado de cada frame capturado.
     */
    suspend fun startCapture(
        lifecycleOwner: LifecycleOwner,
        onFrameCaptured: (CaptureResult) -> Unit
    ) {
        // Encerra sessão ativa anterior se existir — garante estado limpo
        if (cameraProvider != null) {
            Log.w("CameraCapture", "startCapture() chamado com sessão ativa — forçando encerramento")
            stopCapture()
        }

        // Recria o executor se tiver sido encerrado por uma captura anterior
        if (analysisExecutor.isShutdown) {
            analysisExecutor = Executors.newSingleThreadExecutor()
        }

        // Valida hardware antes de qualquer acesso ao sistema de câmera
        if (!hasPhysicalCamera()) {
            onFrameCaptured(CaptureResult.Error(CaptureError.NO_CAMERA))
            return
        }

        // Valida permissão em tempo de execução
        if (!hasCameraPermission()) {
            onFrameCaptured(CaptureResult.Error(CaptureError.PERMISSION_DENIED))
            return
        }

        try {
            val provider = awaitCameraProvider()
            cameraProvider = provider

            // Seleciona a câmera traseira — câmera principal para vistorias de veículos
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Declarado como var antes do imageAnalysis para que o analyzer possa capturá-lo
            // por referência via closure. O valor é preenchido após o bind da câmera;
            // como frames só chegam depois que a câmera está ativa, a leitura no analyzer
            // sempre encontrará o ID correto.
            var physicalCameraId = ""

            // Configura análise de imagem para receber frames em formato YUV antes de qualquer
            // compressão pelo codec do sistema operacional
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        // Timestamp de relógio de parede capturado imediatamente ao receber o frame.
                        // Usa System.currentTimeMillis() (epoch Unix em ms) em vez de
                        // imageProxy.imageInfo.timestamp (clock monotônico desde o boot — não é Unix epoch).
                        val capturedAtMs = System.currentTimeMillis()

                        // Camadas 1 e 3 do ADR-001 integradas aqui:
                        // Camada 1 — frame chega diretamente do pipeline Camera2, sem passar
                        //            pelo codec do SO (YUV_420_888, pré-compressão).
                        // Camada 3 — hash SHA-256 calculado sobre os planos YUV brutos antes
                        //            de qualquer transformação, garantindo que o manifesto
                        //            C2PA referencia o dado original do sensor.
                        val frameHash = FrameHasher.computeHash(imageProxy)
                        onFrameCaptured(CaptureResult.Success(imageProxy, frameHash, physicalCameraId, capturedAtMs))
                    }
                }

            // Encerra qualquer sessão anterior antes de abrir uma nova — garante exclusividade
            provider.unbindAll()

            // Vincula o use case ao ciclo de vida; a câmera é aberta fisicamente aqui
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )

            // Obtém o controle Camera2 para aplicar configurações determinísticas de captura
            val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)

            // Aplica configurações Provvi: desativa HDR, NR mínima, sem edge enhancement
            // Deve ser chamado imediatamente após o bind, antes dos primeiros frames chegarem
            applyProvviCaptureSettings(camera2Control)

            // ID físico da câmera atribuído após o bind — frames só chegam depois deste ponto,
            // portanto o analyzer sempre lê o valor correto via closure
            physicalCameraId = Camera2CameraInfo.from(camera!!.cameraInfo).cameraId

        } catch (e: Exception) {
            // Falha ao vincular a câmera — tipicamente causada por outro processo com acesso exclusivo
            onFrameCaptured(CaptureResult.Error(CaptureError.CAMERA_IN_USE))
        }
    }

    /**
     * Para a captura e libera todos os recursos de câmera e executor.
     * Deve ser chamado quando a sessão de vistoria for encerrada ou o componente destruído.
     */
    fun stopCapture() {
        // Desvincula todos os use cases, sinalizando ao sistema que o hardware pode ser liberado
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null

        // Encerra o executor de análise para evitar vazamento de threads em background
        if (!analysisExecutor.isShutdown) {
            analysisExecutor.shutdown()
        }
    }
}
