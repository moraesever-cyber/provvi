package br.com.provvi.provvi_demo

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import br.com.provvi.ProvviCapture
import br.com.provvi.assertions.GenericAssertions
import br.com.provvi.camera.CaptureError
import br.com.provvi.camera.CaptureResult
import androidx.lifecycle.ProcessLifecycleOwner
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity() {

    private val CHANNEL = "br.com.provvi/sdk"
    private val PERMISSIONS_REQUEST_CODE = 100
    private lateinit var provviCapture: ProvviCapture
    private lateinit var backendClient: br.com.provvi.backend.ProvviBackendClient
    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        provviCapture = ProvviCapture(this)
        backendClient = br.com.provvi.backend.ProvviBackendClient(
            br.com.provvi.backend.BackendConfig(
                lambdaUrl = "https://3nw6hxeumaqhtkrtghjtkzyamq0sojrk.lambda-url.sa-east-1.on.aws/",
                apiKey    = "1fc529447d15beb301f843be82701eed500e86a125c1179d92b642ecd6c77488"
            )
        )

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "capture" -> {
                        val referenceId = call.argument<String>("referenceId") ?: "demo"
                        val capturedBy = call.argument<String>("capturedBy") ?: "Provvi Demo"
                        handleCapture(referenceId, capturedBy, result)
                    }
                    "checkPermissions" -> {
                        result.success(hasRequiredPermissions())
                    }
                    "requestPermissions" -> {
                        pendingResult = result
                        requestPermissions()
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun handleCapture(
        referenceId: String,
        capturedBy: String,
        result: MethodChannel.Result
    ) {
        if (!hasRequiredPermissions()) {
            result.error("PERMISSION_DENIED", "Permissões necessárias não concedidas", null)
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val outcome = provviCapture.capture(
                lifecycleOwner = ProcessLifecycleOwner.get(),
                assertions = GenericAssertions(
                    capturedBy  = capturedBy,
                    referenceId = referenceId
                ),
                backendClient = backendClient
            )

            when (outcome) {
                is br.com.provvi.CaptureOutcome.Success -> {
                    val session = outcome.session
                    result.success(mapOf(
                        "sessionId"            to session.sessionId,
                        "manifestJson"         to session.manifestJson,
                        "frameHashHex"         to session.frameHashHex,
                        "locationSuspicious"   to session.locationSuspicious,
                        "capturedAtNanos"      to session.capturedAtNanos,
                        "hasIntegrityToken"    to session.deviceIntegrityToken.isNotEmpty(),
                        "manifestUrl"          to (session.manifestUrl ?: ""),
                        "pipelineTimings"      to (session.pipelineTimingsMs ?: emptyMap<String, Long>())
                    ))
                }
                is br.com.provvi.CaptureOutcome.SigningFailed ->
                    result.error("SIGNING_FAILED", outcome.reason, null)
                is br.com.provvi.CaptureOutcome.CaptureError ->
                    result.error("CAPTURE_ERROR", outcome.reason, null)
                br.com.provvi.CaptureOutcome.PermissionDenied ->
                    result.error("PERMISSION_DENIED", "Permissão negada", null)
                br.com.provvi.CaptureOutcome.DeviceCompromised ->
                    result.error("DEVICE_COMPROMISED", "Dispositivo comprometido", null)
                br.com.provvi.CaptureOutcome.MockLocationDetected ->
                    result.error("MOCK_LOCATION", "Localização simulada detectada", null)
                is br.com.provvi.CaptureOutcome.RecaptureSuspected ->
                    result.error(
                        "RECAPTURE_SUSPECTED",
                        "Possível recaptura detectada (score: ${"%.2f".format(outcome.score)}, indicadores: ${outcome.indicators.joinToString()})",
                        null
                    )
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSIONS_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            pendingResult?.success(hasRequiredPermissions())
            pendingResult = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        provviCapture.release()
    }
}
