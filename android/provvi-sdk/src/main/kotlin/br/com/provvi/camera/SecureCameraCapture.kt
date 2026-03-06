package br.com.provvi.camera

import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
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
        val physicalCameraId: String
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
                        // Camadas 1 e 3 do ADR-001 integradas aqui:
                        // Camada 1 — frame chega diretamente do pipeline Camera2, sem passar
                        //            pelo codec do SO (YUV_420_888, pré-compressão).
                        // Camada 3 — hash SHA-256 calculado sobre os planos YUV brutos antes
                        //            de qualquer transformação, garantindo que o manifesto
                        //            C2PA referencia o dado original do sensor.
                        val frameHash = FrameHasher.computeHash(imageProxy)
                        onFrameCaptured(CaptureResult.Success(imageProxy, frameHash, physicalCameraId))
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

            // Acesso de baixo nível via Camera2 interop para metadados e controle avançado
            // de hardware (exposição, foco manual, etc.) em versões futuras do SDK
            @Suppress("UNUSED_VARIABLE")
            val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)

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
