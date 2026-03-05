package br.com.provvi.recapture

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Tipos públicos
// ---------------------------------------------------------------------------

/**
 * Indicadores individuais de recaptura identificados pela análise.
 * Cada valor corresponde a um artefato físico distinto produzido por telas digitais.
 */
enum class RecaptureIndicator {
    /** Picos periódicos no espectro de frequência — interferência entre grade de pixels e sensor */
    MOIRE_PATTERN,

    /** Reflexo especular de superfície plana uniforme — tela lisa vs. objeto físico com textura */
    SPECULAR_REFLECTION,

    /** Micro-variação de crominância em alta frequência — padrão de subpixels RGB de tela */
    SCREEN_CHROMATIC_PATTERN
}

/**
 * Resultado da análise de recaptura para uma imagem capturada.
 */
sealed class RecaptureAnalysis {

    /**
     * Imagem provavelmente real — nenhum artefato de tela significativo detectado.
     * @param score Confiança na autenticidade: 0.0 (baixa) a 1.0 (alta).
     */
    data class Clean(val score: Float) : RecaptureAnalysis()

    /**
     * Possível recaptura detectada — um ou mais indicadores de tela superaram os limiares.
     * @param score Score combinado de suspeita: 0.0 (baixa) a 1.0 (alta).
     * @param indicators Lista dos indicadores individuais que dispararam.
     */
    data class Suspicious(
        val score:      Float,
        val indicators: List<RecaptureIndicator>
    ) : RecaptureAnalysis()

    /**
     * Análise inconclusiva — imagem insuficiente para análise (muito escura ou muito pequena).
     */
    data object Inconclusive : RecaptureAnalysis()
}

// ---------------------------------------------------------------------------
// Detector principal (ADR-002, Caminho 1)
// ---------------------------------------------------------------------------

/**
 * Detecta recaptura de tela por análise exclusiva de artefatos físicos, sem ML (ADR-002, Caminho 1).
 *
 * Telas digitais produzem três classes de artefatos detectáveis:
 * - **Moiré** (peso 0.5): interferência entre grade de pixels e sensor da câmera,
 *   visível como picos periódicos no espectro de frequência (FFT 2D, DFT direta).
 * - **Reflexo especular** (peso 0.3): superfície plana uniforme concentra reflexo
 *   em área pequena com luminância extrema — perfil distinto de objetos físicos.
 * - **Padrão cromático** (peso 0.2): subpixels RGB de tela produzem micro-variação
 *   de crominância em alta frequência ausente em superfícies físicas iluminadas.
 *
 * Nenhum dos caminhos segue o pipeline patenteado da Truepic
 * (score ML + score estatístico → probabilidade combinada). Ver ADR-002.
 */
object RecaptureDetector {

    // Dimensão do lado do quadrado para amostragem antes da FFT.
    // 256×256 oferece resolução espectral adequada para detectar grades de pixels
    // mantendo a DFT tratável sem biblioteca externa (O(N²) por linha/coluna).
    private const val FFT_SIZE = 256

    // Limiares individuais para ativar cada indicador
    private const val THRESHOLD_MOIRE      = 0.5f
    private const val THRESHOLD_SPECULAR   = 0.4f
    private const val THRESHOLD_CHROMATIC  = 0.5f

    // Score combinado acima do qual a captura é bloqueada como suspeita
    private const val THRESHOLD_SUSPICIOUS = 0.6f

    // Média de luminância abaixo da qual a análise não pode ser conclusiva
    private const val MIN_LUMINANCE_MEAN   = 30f

    /**
     * Analisa um frame YUV_420_888 e retorna o veredicto de recaptura.
     *
     * O [ImageProxy] não é fechado aqui — responsabilidade do chamador.
     * Deve ser chamado antes de [ProvviCapture.yuvToJpeg] enquanto os buffers
     * YUV ainda estão válidos.
     *
     * @param imageProxy Frame YUV ainda aberto, originado do pipeline CameraX.
     * @return [RecaptureAnalysis] com veredicto e indicadores ativos.
     */
    fun analyze(imageProxy: ImageProxy): RecaptureAnalysis {
        val width  = imageProxy.width
        val height = imageProxy.height

        // Rejeita frames muito pequenos — amostragem abaixo de 64×64 não é representativa
        if (width < 64 || height < 64) return RecaptureAnalysis.Inconclusive

        // Extrai o plano Y (luminância) descartando padding de linha e pixel
        val yPlane = extractPlane(imageProxy.planes[0], width, height)

        // Imagens muito escuras não contêm informação espectral suficiente
        val meanLuminance = yPlane.map { it.toInt() and 0xFF }.average().toFloat()
        if (meanLuminance < MIN_LUMINANCE_MEAN) return RecaptureAnalysis.Inconclusive

        // Extrai planos de crominância U (Cb) e V (Cr) — metade da resolução por dimensão
        val halfW  = width  / 2
        val halfH  = height / 2
        val uPlane = extractPlane(imageProxy.planes[1], halfW, halfH)
        val vPlane = extractPlane(imageProxy.planes[2], halfW, halfH)

        // Executa as três análises independentes (ver ADR-002, Caminho 1)
        val scoreMoire     = detectMoire(yPlane, width, height)
        val scoreSpecular  = detectSpecularReflection(yPlane, width, height)
        val scoreChromatic = detectScreenChromaticPattern(uPlane, vPlane, halfW, halfH)

        // Combina os scores por pesos ponderados (Moiré é o sinal mais confiável)
        val scoreCombined = (scoreMoire * 0.5f) + (scoreSpecular * 0.3f) + (scoreChromatic * 0.2f)

        return if (scoreCombined > THRESHOLD_SUSPICIOUS) {
            // Coleta os indicadores individuais que superaram seus limiares próprios
            val activeIndicators = buildList {
                if (scoreMoire     > THRESHOLD_MOIRE)     add(RecaptureIndicator.MOIRE_PATTERN)
                if (scoreSpecular  > THRESHOLD_SPECULAR)  add(RecaptureIndicator.SPECULAR_REFLECTION)
                if (scoreChromatic > THRESHOLD_CHROMATIC) add(RecaptureIndicator.SCREEN_CHROMATIC_PATTERN)
            }
            RecaptureAnalysis.Suspicious(scoreCombined, activeIndicators)
        } else {
            // Score de autenticidade é o complemento do score de suspeita
            RecaptureAnalysis.Clean(1.0f - scoreCombined)
        }
    }

    /**
     * Serializa o resultado como asserção customizada para inclusão no manifesto C2PA.
     *
     * @param analysis Resultado produzido por [analyze].
     * @return Mapa com chaves compatíveis com o schema de asserções do Provvi SDK.
     */
    fun toManifestAssertion(analysis: RecaptureAnalysis): Map<String, Any> = buildMap {
        // Versão do algoritmo — permite rastrear mudanças de limiar em auditorias futuras
        put("recapture_analysis_version", "1.0")
        // Identificador do método — diferencia do pipeline Truepic para fins legais (ADR-002)
        put("recapture_method", "physical_artifact_analysis_no_ml")

        when (analysis) {
            is RecaptureAnalysis.Clean -> {
                put("recapture_verdict",       "clean")
                put("authenticity_score",      analysis.score)
                put("indicators_triggered",    emptyList<String>())
            }
            is RecaptureAnalysis.Suspicious -> {
                put("recapture_verdict",       "suspicious")
                put("suspicion_score",         analysis.score)
                put("indicators_triggered",    analysis.indicators.map { it.name })
            }
            RecaptureAnalysis.Inconclusive -> {
                put("recapture_verdict",       "inconclusive")
                put("inconclusive_reason",     "insufficient_image_data")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Análise 1 — Padrão de Moiré via DFT 2D
    // ---------------------------------------------------------------------------

    /**
     * Detecta padrão de Moiré por análise espectral de frequência (DFT 2D, fórmula direta).
     *
     * Telas digitais possuem grade regular de pixels. Quando fotografadas, a interferência
     * entre essa grade e o array de sensores da câmera produz padrões periódicos.
     * No domínio da frequência, esses padrões aparecem como picos proeminentes na banda
     * de frequências médias (20–80 ciclos por comprimento FFT_SIZE).
     *
     * Estratégia 2D separável:
     * 1. Amostra a imagem para [FFT_SIZE]×[FFT_SIZE] para manter a DFT tratável.
     * 2. Aplica [computeDFT] linha por linha (DFTs horizontais).
     * 3. Aplica [computeDFT] coluna por coluna sobre as partes reais (DFTs verticais).
     * 4. Calcula o espectro de magnitude e analisa a banda 20–80 ciclos.
     *
     * Score alto quando energia média na banda excede 3× a energia média global.
     *
     * @param yPlane Plano Y extraído sem padding, tamanho width×height.
     * @param width  Largura do frame original.
     * @param height Altura do frame original.
     * @return Score 0.0–1.0 (1.0 = Moiré forte).
     */
    private fun detectMoire(yPlane: ByteArray, width: Int, height: Int): Float {
        // Reduz a imagem para FFT_SIZE×FFT_SIZE por amostragem uniforme (nearest neighbor).
        // Evita O(N⁴) sobre o frame original em resolução completa.
        val sampled = FloatArray(FFT_SIZE * FFT_SIZE)
        for (row in 0 until FFT_SIZE) {
            val srcRow = (row.toLong() * height / FFT_SIZE).toInt()
            for (col in 0 until FFT_SIZE) {
                val srcCol = (col.toLong() * width / FFT_SIZE).toInt()
                sampled[row * FFT_SIZE + col] =
                    (yPlane[srcRow * width + srcCol].toInt() and 0xFF).toFloat()
            }
        }

        // Remove o componente DC (média global) para evitar que o pico de frequência zero
        // domine o espectro e mascare os picos de Moiré nas frequências médias
        val mean = sampled.average().toFloat()
        for (i in sampled.indices) sampled[i] -= mean

        // Passo 1: DFT ao longo das linhas — transforma cada linha de FFT_SIZE pixels
        // Mantém parte real e imaginária intercaladas: [re0, im0, re1, im1, ...]
        val rowSpectrumRe = Array(FFT_SIZE) { FloatArray(FFT_SIZE) }
        val rowSpectrumIm = Array(FFT_SIZE) { FloatArray(FFT_SIZE) }
        for (row in 0 until FFT_SIZE) {
            val rowSignal = FloatArray(FFT_SIZE) { sampled[row * FFT_SIZE + it] }
            val dft = computeDFT(rowSignal)
            for (col in 0 until FFT_SIZE) {
                rowSpectrumRe[row][col] = dft[col * 2]
                rowSpectrumIm[row][col] = dft[col * 2 + 1]
            }
        }

        // Passo 2: DFT ao longo das colunas sobre a parte real dos resultados das linhas.
        // Usa apenas a parte real como sinal de entrada — simplificação válida para
        // detecção de picos de magnitude, onde a estrutura periódica se manifesta
        // independentemente da fase.
        val magnitudes = FloatArray(FFT_SIZE * FFT_SIZE)
        for (col in 0 until FFT_SIZE) {
            val colSignal = FloatArray(FFT_SIZE) { rowSpectrumRe[it][col] }
            val dft = computeDFT(colSignal)
            for (row in 0 until FFT_SIZE) {
                val re = dft[row * 2]
                val im = dft[row * 2 + 1]
                magnitudes[row * FFT_SIZE + col] = sqrt(re * re + im * im)
            }
        }

        // Analisa o espectro de magnitude: compara energia na banda 20–80 ciclos
        // com a energia média global. A distância ao canto superior esquerdo (origem)
        // representa a frequência espacial radial no espectro 2D.
        var sumBand  = 0.0
        var countBand = 0
        var sumAll   = 0.0

        for (row in 0 until FFT_SIZE) {
            for (col in 0 until FFT_SIZE) {
                val mag  = magnitudes[row * FFT_SIZE + col]
                sumAll  += mag
                // Frequência radial a partir da origem do espectro
                val freq = sqrt((row * row + col * col).toFloat())
                if (freq in 20f..80f) {
                    sumBand  += mag
                    countBand++
                }
            }
        }

        if (countBand == 0 || sumAll == 0.0) return 0f

        val meanAll  = sumAll  / (FFT_SIZE * FFT_SIZE)
        val meanBand = sumBand / countBand

        // Razão entre energia da banda e energia global, normalizada pelo limiar de 3×.
        // Razão = 3.0 → score = 1.0 (Moiré forte). Razão ≤ 1.0 → score = 0 (sinal plano).
        val ratio = (meanBand / meanAll).toFloat()
        return (ratio / 3f).coerceIn(0f, 1f)
    }

    /**
     * Transformada de Fourier Discreta (DFT) 1D pela fórmula direta.
     *
     * X[k] = Σ(n=0..N-1)  x[n] · e^(-j·2π·k·n / N)
     *       = Σ(n=0..N-1)  x[n] · [cos(2π·k·n/N) - j·sin(2π·k·n/N)]
     *
     * Complexidade O(N²) — aceitável para N = [FFT_SIZE] em análise one-shot.
     * Nenhuma biblioteca externa utilizada (requisito ADR-002).
     *
     * @param signal Sinal de entrada com N amostras reais.
     * @return Array de 2N floats: partes real e imaginária intercaladas [re0, im0, re1, im1, ...].
     */
    private fun computeDFT(signal: FloatArray): FloatArray {
        val n          = signal.size
        val result     = FloatArray(n * 2)
        val twoPiOverN = 2.0 * Math.PI / n

        for (k in 0 until n) {
            var re = 0.0
            var im = 0.0
            val phaseStep = twoPiOverN * k
            for (t in 0 until n) {
                val angle = phaseStep * t
                re += signal[t] * cos(angle)
                im -= signal[t] * sin(angle)
            }
            result[k * 2]     = re.toFloat()
            result[k * 2 + 1] = im.toFloat()
        }

        return result
    }

    // ---------------------------------------------------------------------------
    // Análise 2 — Reflexo especular
    // ---------------------------------------------------------------------------

    /**
     * Detecta o perfil de reflexo especular característico de superfícies planas (telas).
     *
     * Telas lisas concentram o reflexo em uma área pequena com luminância muito alta (Y > 240).
     * Objetos físicos com textura difundem a luz de forma mais uniforme — poucos pixels
     * atingem luminância extrema, ou muitos o fazem (overexposure legítimo).
     *
     * A janela suspeita é 0.5%–8% de pixels com Y > 240:
     * - Abaixo de 0.5%: reflexo insuficiente — objeto físico ou tela muito inclinada.
     * - Acima de 8%: overexposure geral — não indicativo de tela especificamente.
     * - Entre 0.5% e 8%: perfil compatível com reflexo especular de tela.
     *
     * Score máximo na proporção de ~2% (centro da janela), decaindo linearmente para as bordas.
     *
     * @param yPlane Plano Y extraído sem padding.
     * @param width  Largura do frame.
     * @param height Altura do frame.
     * @return Score 0.0–1.0 (1.0 = perfil de reflexo de tela máximo).
     */
    private fun detectSpecularReflection(yPlane: ByteArray, width: Int, height: Int): Float {
        val totalPixels = width * height

        // Conta pixels com luminância acima do limiar de brilho extremo (> 240 de 255)
        var brightCount = 0
        for (byte in yPlane) {
            if ((byte.toInt() and 0xFF) > 240) brightCount++
        }

        val brightRatio = brightCount.toFloat() / totalPixels
        val minRatio    = 0.005f  // 0.5%
        val maxRatio    = 0.08f   // 8%

        // Fora da janela suspeita → score zero
        if (brightRatio < minRatio || brightRatio > maxRatio) return 0f

        // Dentro da janela: score com pico no centro (~2%), decaindo linearmente para bordas
        val center = (minRatio + maxRatio) / 2f
        val range  = (maxRatio - minRatio) / 2f
        val dist   = abs(brightRatio - center) / range
        return (1f - dist).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------
    // Análise 3 — Padrão cromático de tela
    // ---------------------------------------------------------------------------

    /**
     * Detecta variação cromática de alta frequência produzida por subpixels RGB de telas.
     *
     * Telas digitais emitem luz por subpixels R, G e B dispostos em grade regular.
     * Quando fotografadas, essa grade produz micro-variação de crominância (U e V)
     * em alta frequência que não existe em objetos físicos iluminados por luz contínua.
     *
     * A análise calcula a variância de U e V em janelas 4×4 sobre os planos de crominância.
     * Score alto se a variância média excede o limiar empírico de objetos físicos (~30.0):
     * - Objetos físicos: variância cromática local geralmente < 30.
     * - Telas digitais:  variância cromática local geralmente > 50 (grade de subpixels).
     *
     * @param uPlane Plano de crominância U (Cb), tamanho halfW×halfH.
     * @param vPlane Plano de crominância V (Cr), tamanho halfW×halfH.
     * @param halfW  Largura dos planos U/V (metade da largura do frame original).
     * @param halfH  Altura dos planos U/V (metade da altura do frame original).
     * @return Score 0.0–1.0 (1.0 = padrão cromático de tela forte).
     */
    private fun detectScreenChromaticPattern(
        uPlane: ByteArray,
        vPlane: ByteArray,
        halfW:  Int,
        halfH:  Int
    ): Float {
        val windowSize     = 4   // Janela 4×4 pixels sobre os planos de crominância
        var totalVariance  = 0.0
        var windowCount    = 0

        // Percorre os planos U/V em janelas não sobrepostas de 4×4
        var row = 0
        while (row + windowSize <= halfH) {
            var col = 0
            while (col + windowSize <= halfW) {
                val n    = windowSize * windowSize
                var sumU = 0.0; var sumSqU = 0.0
                var sumV = 0.0; var sumSqV = 0.0

                for (wr in 0 until windowSize) {
                    for (wc in 0 until windowSize) {
                        val idx = (row + wr) * halfW + (col + wc)
                        val u   = (uPlane[idx].toInt() and 0xFF).toDouble()
                        val v   = (vPlane[idx].toInt() and 0xFF).toDouble()
                        sumU  += u;  sumSqU += u * u
                        sumV  += v;  sumSqV += v * v
                    }
                }

                // Variância = E[X²] − E[X]², fórmula do momento de segunda ordem
                val varU = (sumSqU / n) - (sumU / n) * (sumU / n)
                val varV = (sumSqV / n) - (sumV / n) * (sumV / n)
                // Média dos dois canais de crominância para uma medida única por janela
                totalVariance += (varU + varV) / 2.0
                windowCount++

                col += windowSize
            }
            row += windowSize
        }

        if (windowCount == 0) return 0f

        val meanVariance = totalVariance / windowCount

        // Limiares empíricos baseados na separação entre objetos físicos e telas digitais:
        // - threshold (30): variância típica de objetos físicos em luz ambiente
        // - maxVariance (100): variância típica de telas de alta resolução
        val threshold   = 30.0
        val maxVariance = 100.0

        return ((meanVariance - threshold) / (maxVariance - threshold))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------
    // Utilitários internos
    // ---------------------------------------------------------------------------

    /**
     * Extrai os bytes efetivos de um plano YUV descartando padding de linha e pixelStride.
     *
     * Análogo ao [FrameHasher.extractPlaneBytes], mas local ao RecaptureDetector para
     * evitar dependência circular entre pacotes. Retorna um byte por pixel, sem gaps.
     *
     * @param plane  Plano de cor do frame.
     * @param width  Largura efetiva do plano em pixels.
     * @param height Altura efetiva do plano em pixels.
     * @return ByteArray compacto com width×height bytes.
     */
    private fun extractPlane(plane: ImageProxy.PlaneProxy, width: Int, height: Int): ByteArray {
        val buffer      = plane.buffer
        val rowStride   = plane.rowStride
        val pixelStride = plane.pixelStride
        val output      = ByteArray(width * height)
        var idx         = 0

        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                output[idx++] = buffer.get(rowStart + col * pixelStride)
            }
        }

        return output
    }
}
