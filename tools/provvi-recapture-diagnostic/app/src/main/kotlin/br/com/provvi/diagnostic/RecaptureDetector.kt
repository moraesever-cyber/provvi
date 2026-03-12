package br.com.provvi.diagnostic

// Copiado de android/provvi-sdk — NÃO editar o SDK a partir daqui.
// Modificações de calibração devem ser aplicadas no SDK após validação com esta ferramenta.

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Tipos públicos
// ---------------------------------------------------------------------------

enum class RecaptureIndicator {
    MOIRE_PATTERN,
    SPECULAR_REFLECTION,
    SCREEN_CHROMATIC_PATTERN,
    SCREEN_EDGE_SHARPNESS
}

sealed class RecaptureAnalysis {
    data class Clean(
        val score:  Float,
        val scores: Map<String, Float> = emptyMap()
    ) : RecaptureAnalysis()

    data class Suspicious(
        val score:      Float,
        val indicators: List<RecaptureIndicator>,
        val scores:     Map<String, Float> = emptyMap()
    ) : RecaptureAnalysis()

    data object Inconclusive : RecaptureAnalysis()
}

// ---------------------------------------------------------------------------
// Scores diagnósticos — expostos apenas nesta ferramenta
// ---------------------------------------------------------------------------

/** Scores individuais de cada análise, para calibração quantitativa via CSV. */
data class DiagnosticScores(
    val scoreMoire:     Float,
    val scoreSpecular:  Float,
    val scoreChromatic: Float,
    val scoreEdge:      Float,
    val scoreCombined:  Float,
    val meanLuminance:  Float,
    val verdict:        String  // "NONE", "MEDIUM", "BLOCK", "INCONCLUSIVE"
)

// ---------------------------------------------------------------------------
// Detector (cópia de produção — v1.2/v1.5)
// ---------------------------------------------------------------------------

object RecaptureDetector {

    private const val FFT_SIZE = 128

    private const val THRESHOLD_MOIRE      = 0.30f
    private const val THRESHOLD_SPECULAR   = 0.40f
    private const val THRESHOLD_CHROMATIC  = 0.50f
    private const val THRESHOLD_EDGE       = 0.30f

    private const val THRESHOLD_SUSPICIOUS = 0.35f
    const val         THRESHOLD_BLOCK      = 0.65f

    private const val MIN_LUMINANCE_MEAN   = 8f

    private const val EDGE_SHARP_THRESHOLD = 42
    private const val EDGE_SOFT_THRESHOLD  = 15

    // ---------------------------------------------------------------------------
    // API pública
    // ---------------------------------------------------------------------------

    fun analyze(imageProxy: ImageProxy): RecaptureAnalysis {
        val scores = analyzeWithScores(imageProxy)
        return when (scores.verdict) {
            "INCONCLUSIVE" -> RecaptureAnalysis.Inconclusive
            "NONE"         -> RecaptureAnalysis.Clean(1.0f - scores.scoreCombined, scores.toMap())
            else           -> {
                val indicators = buildList {
                    if (scores.scoreMoire     > THRESHOLD_MOIRE)     add(RecaptureIndicator.MOIRE_PATTERN)
                    if (scores.scoreSpecular  > THRESHOLD_SPECULAR)  add(RecaptureIndicator.SPECULAR_REFLECTION)
                    if (scores.scoreChromatic > THRESHOLD_CHROMATIC) add(RecaptureIndicator.SCREEN_CHROMATIC_PATTERN)
                    if (scores.scoreEdge      > THRESHOLD_EDGE)      add(RecaptureIndicator.SCREEN_EDGE_SHARPNESS)
                }
                RecaptureAnalysis.Suspicious(scores.scoreCombined, indicators, scores.toMap())
            }
        }
    }

    /**
     * Versão diagnóstica — expõe todos os scores intermediários.
     * Use apenas nesta ferramenta de calibração.
     */
    fun analyzeWithScores(imageProxy: ImageProxy): DiagnosticScores {
        val width  = imageProxy.width
        val height = imageProxy.height

        if (width < 64 || height < 64) {
            return DiagnosticScores(0f, 0f, 0f, 0f, 0f, 0f, "INCONCLUSIVE")
        }

        val strideMoire = if (width > 1080) 2 else 1
        val strideOther = if (width >= 640) 2 else 1

        val yPlaneOther = extractPlaneStrided(imageProxy.planes[0], width, height, strideOther)
        val effectiveW  = width  / strideOther
        val effectiveH  = height / strideOther

        val meanLuminance = yPlaneOther.map { it.toInt() and 0xFF }.average().toFloat()
        if (meanLuminance < MIN_LUMINANCE_MEAN) {
            return DiagnosticScores(0f, 0f, 0f, 0f, 0f, meanLuminance, "INCONCLUSIVE")
        }

        val halfOrigW = width  / 2
        val halfOrigH = height / 2
        val uPlane    = extractPlaneStrided(imageProxy.planes[1], halfOrigW, halfOrigH, strideOther)
        val vPlane    = extractPlaneStrided(imageProxy.planes[2], halfOrigW, halfOrigH, strideOther)
        val halfW     = effectiveW / 2
        val halfH     = effectiveH / 2

        val yMoire  = if (strideMoire == strideOther) yPlaneOther
                      else extractPlaneStrided(imageProxy.planes[0], width, height, strideMoire)
        val moireW  = width  / strideMoire
        val moireH  = height / strideMoire

        val scoreMoire     = detectMoire(yMoire, moireW, moireH)
        val scoreSpecular  = detectSpecularReflection(yPlaneOther, effectiveW, effectiveH)
        val scoreChromatic = detectScreenChromaticPattern(uPlane, vPlane, halfW, halfH)
        val scoreEdge      = detectEdgeSharpness(yMoire, moireW, moireH)

        val scoreCombined = (scoreMoire     * 0.25f) +
                            (scoreSpecular  * 0.25f) +
                            (scoreChromatic * 0.10f) +
                            (scoreEdge      * 0.40f)

        val verdict = when {
            scoreCombined > THRESHOLD_BLOCK      -> "BLOCK"
            scoreCombined > THRESHOLD_SUSPICIOUS -> "MEDIUM"
            else                                 -> "NONE"
        }

        return DiagnosticScores(
            scoreMoire, scoreSpecular, scoreChromatic, scoreEdge,
            scoreCombined, meanLuminance, verdict
        )
    }

    // ---------------------------------------------------------------------------
    // Análise 1 — Moiré via DFT 2D
    // ---------------------------------------------------------------------------

    private fun detectMoire(yPlane: ByteArray, width: Int, height: Int): Float {
        val sampled = FloatArray(FFT_SIZE * FFT_SIZE)
        for (row in 0 until FFT_SIZE) {
            val srcRow = (row.toLong() * height / FFT_SIZE).toInt()
            for (col in 0 until FFT_SIZE) {
                val srcCol = (col.toLong() * width / FFT_SIZE).toInt()
                sampled[row * FFT_SIZE + col] =
                    (yPlane[srcRow * width + srcCol].toInt() and 0xFF).toFloat()
            }
        }
        val mean = sampled.average().toFloat()
        for (i in sampled.indices) sampled[i] -= mean

        val rowSpectrumRe = Array(FFT_SIZE) { FloatArray(FFT_SIZE) }
        for (row in 0 until FFT_SIZE) {
            val dft = computeDFT(FloatArray(FFT_SIZE) { sampled[row * FFT_SIZE + it] })
            for (col in 0 until FFT_SIZE) rowSpectrumRe[row][col] = dft[col * 2]
        }

        val magnitudes = FloatArray(FFT_SIZE * FFT_SIZE)
        for (col in 0 until FFT_SIZE) {
            val dft = computeDFT(FloatArray(FFT_SIZE) { rowSpectrumRe[it][col] })
            for (row in 0 until FFT_SIZE) {
                val re = dft[row * 2]; val im = dft[row * 2 + 1]
                magnitudes[row * FFT_SIZE + col] = sqrt(re * re + im * im)
            }
        }

        val bandMags = mutableListOf<Float>()
        for (row in 0 until FFT_SIZE) {
            for (col in 0 until FFT_SIZE) {
                val freq = sqrt((row * row + col * col).toFloat())
                if (freq in 20f..80f) bandMags.add(magnitudes[row * FFT_SIZE + col])
            }
        }
        if (bandMags.isEmpty()) return 0f
        val bandMean = bandMags.average()
        if (bandMean == 0.0) return 0f
        val bandVariance = bandMags.sumOf { m ->
            val diff = m.toDouble() - bandMean; diff * diff
        } / bandMags.size
        val cv = (sqrt(bandVariance) / bandMean).toFloat()
        return ((cv - 0.5f) / 2.5f).coerceIn(0f, 1f)
    }

    private fun computeDFT(signal: FloatArray): FloatArray {
        val n = signal.size
        val result = FloatArray(n * 2)
        val twoPiOverN = 2.0 * Math.PI / n
        for (k in 0 until n) {
            var re = 0.0; var im = 0.0
            val phaseStep = twoPiOverN * k
            for (t in 0 until n) {
                val angle = phaseStep * t
                re += signal[t] * cos(angle)
                im -= signal[t] * sin(angle)
            }
            result[k * 2] = re.toFloat(); result[k * 2 + 1] = im.toFloat()
        }
        return result
    }

    // ---------------------------------------------------------------------------
    // Análise 2 — Reflexo especular
    // ---------------------------------------------------------------------------

    private fun detectSpecularReflection(yPlane: ByteArray, width: Int, height: Int): Float {
        val totalPixels = width * height
        var brightCount = 0
        for (byte in yPlane) { if ((byte.toInt() and 0xFF) > 240) brightCount++ }
        val brightRatio = brightCount.toFloat() / totalPixels
        val minRatio = 0.005f; val maxRatio = 0.15f
        if (brightRatio < minRatio || brightRatio > maxRatio) return 0f
        val center = (minRatio + maxRatio) / 2f
        val range  = (maxRatio - minRatio) / 2f
        return (1f - abs(brightRatio - center) / range).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------
    // Análise 3 — Padrão cromático
    // ---------------------------------------------------------------------------

    private fun detectScreenChromaticPattern(
        uPlane: ByteArray, vPlane: ByteArray, halfW: Int, halfH: Int
    ): Float {
        val windowSize = 4; var totalVariance = 0.0; var windowCount = 0
        var row = 0
        while (row + windowSize <= halfH) {
            var col = 0
            while (col + windowSize <= halfW) {
                val n = windowSize * windowSize
                var sumU = 0.0; var sumSqU = 0.0; var sumV = 0.0; var sumSqV = 0.0
                for (wr in 0 until windowSize) for (wc in 0 until windowSize) {
                    val idx = (row + wr) * halfW + (col + wc)
                    val u = (uPlane[idx].toInt() and 0xFF).toDouble()
                    val v = (vPlane[idx].toInt() and 0xFF).toDouble()
                    sumU += u; sumSqU += u * u; sumV += v; sumSqV += v * v
                }
                totalVariance += ((sumSqU / n) - (sumU / n).let { it * it } +
                                  (sumSqV / n) - (sumV / n).let { it * it }) / 2.0
                windowCount++; col += windowSize
            }
            row += windowSize
        }
        if (windowCount == 0) return 0f
        return ((totalVariance / windowCount - 30.0) / 70.0).toFloat().coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------
    // Análise 4 — Nitidez de bordas
    // ---------------------------------------------------------------------------

    private fun detectEdgeSharpness(yPlane: ByteArray, width: Int, height: Int): Float {
        val sampled = Array(FFT_SIZE) { row ->
            IntArray(FFT_SIZE) { col ->
                val srcRow = (row.toLong() * height / FFT_SIZE).toInt()
                val srcCol = (col.toLong() * width  / FFT_SIZE).toInt()
                yPlane[srcRow * width + srcCol].toInt() and 0xFF
            }
        }
        var sharpCount = 0; var softCount = 0
        for (row in 1 until FFT_SIZE - 1) for (col in 1 until FFT_SIZE - 1) {
            val gx  = abs(sampled[row][col + 1] - sampled[row][col - 1])
            val gy  = abs(sampled[row + 1][col] - sampled[row - 1][col])
            val mag = (gx + gy) / 2
            when {
                mag >= EDGE_SHARP_THRESHOLD -> sharpCount++
                mag >= EDGE_SOFT_THRESHOLD  -> softCount++
            }
        }
        val totalEdges = sharpCount + softCount
        if (totalEdges == 0) return 0f
        val sharpRatio = sharpCount.toFloat() / totalEdges
        return ((sharpRatio - 0.2f) / (0.5f - 0.2f)).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------
    // Utilitários
    // ---------------------------------------------------------------------------

    private fun extractPlane(plane: ImageProxy.PlaneProxy, width: Int, height: Int): ByteArray {
        val buffer = plane.buffer; val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride; val output = ByteArray(width * height); var idx = 0
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) output[idx++] = buffer.get(rowStart + col * pixelStride)
        }
        return output
    }

    private fun extractPlaneStrided(
        plane: ImageProxy.PlaneProxy, width: Int, height: Int, stride: Int
    ): ByteArray {
        if (stride == 1) return extractPlane(plane, width, height)
        val buffer = plane.buffer; val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val outW = width / stride; val outH = height / stride
        val output = ByteArray(outW * outH); var idx = 0
        for (row in 0 until outH) {
            val rowStart = (row * stride) * rowStride
            for (col in 0 until outW) output[idx++] = buffer.get(rowStart + col * stride * pixelStride)
        }
        return output
    }
}

// Extensão auxiliar — converte DiagnosticScores para Map<String, Float>
private fun DiagnosticScores.toMap() = mapOf(
    "moire"          to scoreMoire,
    "specular"       to scoreSpecular,
    "chromatic"      to scoreChromatic,
    "edge"           to scoreEdge,
    "combined"       to scoreCombined,
    "mean_luminance" to meanLuminance
)
