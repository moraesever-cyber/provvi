package br.com.provvi.diagnostic

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Adapta um [Bitmap] ARGB_8888 para a interface [ImageProxy] esperada pelo RecaptureDetector.
 *
 * Converte para YUV_420_888 (planos Y, U, V separados) via fórmula BT.601 full-range.
 * Stride = width (sem padding), pixelStride = 1 — formato compacto sem interleaving.
 *
 * Implementa apenas os métodos consumidos por RecaptureDetector.analyzeWithScores().
 * Outros métodos lançam UnsupportedOperationException.
 */
class BitmapImageProxyWrapper(private val bitmap: Bitmap) : ImageProxy {

    private val width  = bitmap.width
    private val height = bitmap.height

    private val yBuffer: ByteBuffer
    private val uBuffer: ByteBuffer
    private val vBuffer: ByteBuffer

    init {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yData = ByteArray(width * height)
        val uData = ByteArray((width / 2) * (height / 2))
        val vData = ByteArray((width / 2) * (height / 2))

        for (row in 0 until height) {
            for (col in 0 until width) {
                val pixel = pixels[row * width + col]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8)  and 0xFF
                val b =  pixel         and 0xFF

                // BT.601 full range
                val y = ((66 * r + 129 * g + 25  * b + 128) shr 8) + 16
                yData[row * width + col] = y.coerceIn(0, 255).toByte()

                if (row % 2 == 0 && col % 2 == 0) {
                    val uvIdx = (row / 2) * (width / 2) + (col / 2)
                    val u = ((-38 * r - 74  * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94  * g -  18 * b + 128) shr 8) + 128
                    uData[uvIdx] = u.coerceIn(0, 255).toByte()
                    vData[uvIdx] = v.coerceIn(0, 255).toByte()
                }
            }
        }

        yBuffer = ByteBuffer.wrap(yData)
        uBuffer = ByteBuffer.wrap(uData)
        vBuffer = ByteBuffer.wrap(vData)
    }

    override fun getWidth()  = width
    override fun getHeight() = height
    override fun getFormat() = ImageFormat.YUV_420_888

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(
        makePlane(yBuffer, width,     1),
        makePlane(uBuffer, width / 2, 1),
        makePlane(vBuffer, width / 2, 1)
    )

    private fun makePlane(buffer: ByteBuffer, rowStride: Int, pixelStride: Int) =
        object : ImageProxy.PlaneProxy {
            override fun getBuffer()      = buffer.apply { rewind() }
            override fun getRowStride()   = rowStride
            override fun getPixelStride() = pixelStride
        }

    override fun close() = Unit
    override fun getCropRect() = android.graphics.Rect(0, 0, width, height)
    override fun setCropRect(rect: android.graphics.Rect?) = Unit
    override fun getImageInfo() = throw UnsupportedOperationException()
    override fun getImage()     = throw UnsupportedOperationException()
}
