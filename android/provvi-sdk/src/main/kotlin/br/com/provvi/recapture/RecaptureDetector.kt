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
    SCREEN_CHROMATIC_PATTERN,

    /**
     * Transições de intensidade abruptas dominantes — bordas perfeitas de texto e ícones de tela
     * vs. gradientes suaves de objetos físicos iluminados por luz contínua.
     */
    SCREEN_EDGE_SHARPNESS
}

/**
 * Resultado da análise de recaptura para uma imagem capturada.
 */
sealed class RecaptureAnalysis {

    /**
     * Imagem provavelmente real — nenhum artefato de tela significativo detectado.
     * @param score  Confiança na autenticidade: 0.0 (baixa) a 1.0 (alta).
     * @param scores Scores individuais para diagnóstico: moire, specular, chromatic, edge, combined.
     */
    data class Clean(
        val score:  Float,
        val scores: Map<String, Float> = emptyMap()
    ) : RecaptureAnalysis()

    /**
     * Possível recaptura detectada — um ou mais indicadores de tela superaram os limiares.
     * @param score      Score combinado de suspeita: 0.0 (baixa) a 1.0 (alta).
     * @param indicators Lista dos indicadores individuais que dispararam.
     * @param scores     Scores individuais para diagnóstico: moire, specular, chromatic, edge, combined.
     */
    data class Suspicious(
        val score:      Float,
        val indicators: List<RecaptureIndicator>,
        val scores:     Map<String, Float> = emptyMap()
    ) : RecaptureAnalysis()

    /**
     * Análise inconclusiva — imagem insuficiente para análise (muito escura ou muito pequena).
     */
    data object Inconclusive : RecaptureAnalysis()
}

// ---------------------------------------------------------------------------
// Detector principal (ADR-002, Caminho 1) — v1.1
// ---------------------------------------------------------------------------

/**
 * Detecta recaptura de tela por análise exclusiva de artefatos físicos, sem ML (ADR-002, Caminho 1).
 *
 * Telas digitais produzem quatro classes de artefatos detectáveis:
 * - **Moiré** (peso 0.25): interferência entre grade de pixels e sensor da câmera,
 *   visível como picos periódicos no espectro de frequência (FFT 2D, DFT direta).
 * - **Reflexo especular** (peso 0.25): superfície plana uniforme concentra reflexo
 *   em área pequena com luminância extrema — perfil distinto de objetos físicos.
 * - **Padrão cromático** (peso 0.10): subpixels RGB de tela produzem micro-variação
 *   de crominância em alta frequência ausente em superfícies físicas iluminadas.
 * - **Nitidez de bordas** (peso 0.40): bordas abruptas de texto/ícones de tela (gradiente ≥ 70)
 *   vs. gradientes suaves de objetos físicos iluminados por luz contínua.
 *
 * **Estratégia de stride separado por análise (v1.7):**
 * Moiré e edge sharpness: strideMoire (stride=1 para frames ≤ 1080px, stride=2 acima)
 * Emissive surface (Y): strideOther=2 para frames ≥ 640px
 * Chromatic (UV): strideChromatic=2 sobre planos nativos YUV 4:2:0 —
 *   resulta em 1/4 da resolução UV original, ~4× mais rápido que stride=1,
 *   mantendo sinal de subpixel detectável.
 *
 * Nenhum dos caminhos segue o pipeline patenteado da Truepic
 * (score ML + score estatístico → probabilidade combinada). Ver ADR-002.
 */
object RecaptureDetector {

    // Dimensão do lado do quadrado para amostragem antes da FFT.
    // 128×128 → 4× menos trabalho que 256×256 (O(N²) por linha/coluna).
    // Resolução de 1/128 ≈ 0.008 por bin — suficiente para detectar grades de
    // pixels (frequências 0.1–0.4 do espectro normalizado).
    private const val FFT_SIZE = 128

    // Limiares individuais para ativar cada indicador
    // CALIBRAÇÃO v1.6: Specular e Chromatic mais sensíveis — indicadores corrigidos agora ativos
    private const val THRESHOLD_MOIRE      = 0.4f
    private const val THRESHOLD_SPECULAR   = 0.3f   // detectEmissiveSurface — sinal novo, limiar menor
    private const val THRESHOLD_CHROMATIC  = 0.2f   // UV sem stride — sinal fraco por design
    private const val THRESHOLD_EDGE       = 0.30f

    // Limiar combinado reduzido de 0.35 → 0.5 para cobrir os indicadores corrigidos.
    // CALIBRAÇÃO NECESSÁRIA: ajustar após validar com dataset expandido.
    private const val THRESHOLD_SUSPICIOUS = 0.5f

    // Limiar superior: score acima deste valor → captura bloqueada imediatamente.
    // Reservado para detecções de alta confiança onde o risco é inequívoco.
    // Exposto publicamente para que ProvviCapture implemente a lógica de dois níveis.
    const val THRESHOLD_BLOCK = 0.65f

    // Média de luminância abaixo da qual a análise não pode ser conclusiva.
    // Reduzido de 30 para 8 — ambientes internos com tela como única fonte de luz
    // produzem mean~12 com o frame dominado por fundo escuro; ainda é analisável.
    private const val MIN_LUMINANCE_MEAN   = 8f

    // Limiares de gradiente para análise de nitidez de bordas (0–255)
    // SHARP: gradiente "muito abrupto" — (~22% de contraste por pixel)
    //   55/255 ≈ 22%: captura bordas de conteúdo fotográfico em telas (transições
    //   de área clara→escura na imagem exibida) e contornos de objetos tridimensionais
    //   em foto de foto, mantendo false-positive baixo em superfícies físicas uniformes.
    //   Histórico de calibração:
    //     42 → falso positivo em asfalto/metal (score_edge=1.0 em objetos reais)
    //     70 → falso negativo em foto de foto a 640×480 (score_edge=0 em telas)
    //     55 → ponto de equilíbrio testado empiricamente
    // SOFT:  mínimo para considerar como borda real (não ruído de sensor)
    private const val EDGE_SHARP_THRESHOLD = 42
    private const val EDGE_SOFT_THRESHOLD  = 15

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

        // Stride para Moiré e nitidez de bordas — preserva a grade de pixels para evitar aliasing.
        // Stride=2 apenas para frames > 1080px, onde a perda de sinal é mínima.
        val strideMoire = if (width > 1080) 2 else 1

        // Stride para specular e chromatic — toleram maior subamostragem sem perda de sinal,
        // pois não dependem de frequências periódicas finas.
        val strideOther = if (width >= 640) 2 else 1

        // Stride específico para os planos UV do Chromatic.
        // stride=2 sobre os planos nativos YUV 4:2:0 (que já têm metade da resolução)
        // resulta em 1/4 da resolução UV original — suficiente para detectar variância
        // de subpixel, ~4× mais rápido que stride=1, sem o double-stride anterior.
        val strideChromatic = if (width >= 640) 2 else 1

        // Plano Y para Moiré e edge sharpness — resolução preservada
        val yPlaneMoire = extractPlaneStrided(imageProxy.planes[0], width, height, strideMoire)
        val moireW      = width  / strideMoire
        val moireH      = height / strideMoire

        // Plano Y para specular — reutiliza o buffer se os strides coincidirem
        val yPlaneOther = if (strideMoire == strideOther) yPlaneMoire
                          else extractPlaneStrided(imageProxy.planes[0], width, height, strideOther)
        val effectiveW  = width  / strideOther
        val effectiveH  = height / strideOther

        // Imagens muito escuras não contêm informação espectral suficiente
        val meanLuminance = yPlaneOther.map { it.toInt() and 0xFF }.average().toFloat()
        if (meanLuminance < MIN_LUMINANCE_MEAN) return RecaptureAnalysis.Inconclusive

        // Planos U/V com strideChromatic — compromisso entre sinal e performance.
        // YUV 4:2:0 já tem metade da resolução; strideChromatic=2 resulta em 1/4 da
        // resolução UV original (vs 1/16 com double-stride anterior — DT-004).
        // Preserva variância de subpixel suficiente para detectScreenChromaticPattern.
        val halfOrigW = width  / 2
        val halfOrigH = height / 2
        val uPlane    = extractPlaneStrided(imageProxy.planes[1], halfOrigW, halfOrigH, strideChromatic)
        val vPlane    = extractPlaneStrided(imageProxy.planes[2], halfOrigW, halfOrigH, strideChromatic)
        val halfW     = halfOrigW / strideChromatic
        val halfH     = halfOrigH / strideChromatic

        // Executa as quatro análises independentes (ver ADR-002, Caminho 1 — v1.1)
        val scoreMoire     = detectMoire(yPlaneMoire, moireW, moireH)
        val scoreSpecular  = detectEmissiveSurface(yPlaneOther, effectiveW, effectiveH)
        val scoreChromatic = detectScreenChromaticPattern(uPlane, vPlane, halfW, halfH)
        val scoreEdge      = detectEdgeSharpness(yPlaneMoire, moireW, moireH)

        // Pesos v1.6 — redistribuídos para Specular (detectEmissiveSurface) e Chromatic (UV sem stride)
        // agora que os dois indicadores produzem sinal real. scoreEdge excluído temporariamente
        // pois apresenta discriminação nula no dataset atual (real=0.47, recaptura=0.48).
        // CALIBRAÇÃO NECESSÁRIA: reintroduzir scoreEdge após validar com dataset expandido.
        val scoreCombined = (scoreMoire     * 0.40f) +
                            (scoreSpecular  * 0.35f) +
                            (scoreChromatic * 0.25f)

        val debugScores = mapOf(
            "moire"          to scoreMoire,
            "specular"       to scoreSpecular,
            "chromatic"      to scoreChromatic,
            "edge"           to scoreEdge,
            "combined"       to scoreCombined,
            "mean_luminance" to meanLuminance
        )

        return if (scoreCombined > THRESHOLD_SUSPICIOUS) {
            val activeIndicators = buildList {
                if (scoreMoire     > THRESHOLD_MOIRE)     add(RecaptureIndicator.MOIRE_PATTERN)
                if (scoreSpecular  > THRESHOLD_SPECULAR)  add(RecaptureIndicator.SPECULAR_REFLECTION)
                if (scoreChromatic > THRESHOLD_CHROMATIC) add(RecaptureIndicator.SCREEN_CHROMATIC_PATTERN)
                if (scoreEdge      > THRESHOLD_EDGE)      add(RecaptureIndicator.SCREEN_EDGE_SHARPNESS)
            }
            RecaptureAnalysis.Suspicious(scoreCombined, activeIndicators, debugScores)
        } else {
            RecaptureAnalysis.Clean(1.0f - scoreCombined, debugScores)
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
        put("recapture_analysis_version", "1.7")
        // Identificador do método — diferencia do pipeline Truepic para fins legais (ADR-002)
        put("recapture_method", "physical_artifact_analysis_no_ml")

        when (analysis) {
            is RecaptureAnalysis.Clean -> {
                put("recapture_verdict",       "clean")
                put("authenticity_score",      analysis.score)
                put("indicators_triggered",    emptyList<String>())
                putAll(analysis.scores.mapKeys { "score_${it.key}" })
            }
            is RecaptureAnalysis.Suspicious -> {
                put("recapture_verdict",       "suspicious")
                put("suspicion_score",         analysis.score)
                put("indicators_triggered",    analysis.indicators.map { it.name })
                putAll(analysis.scores.mapKeys { "score_${it.key}" })
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
     * @param yPlane Plano Y extraído com stride=1 ou stride=2 para frames > 1080px.
     * @param width  Largura efetiva do plano Y recebido.
     * @param height Altura efetiva do plano Y recebido.
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

        // Coleta magnitudes da banda de interesse (20–80 ciclos).
        // Moiré produz picos discretos nessa faixa — o espectro fica "espigado".
        // Imagens naturais têm decaimento suave 1/f — o espectro dentro da banda é homogêneo.
        //
        // Métrica: coeficiente de variação (CV = desvio_padrão / média) dentro da banda.
        // CV alto → picos concentrados → Moiré presente.
        // CV baixo → espectro uniforme → imagem natural.
        //
        // Esta abordagem elimina o viés do espectro 1/f: ao comparar dentro da própria banda,
        // a média da banda é a referência — não a média global dominada pelas baixas frequências.
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

        // cv < 0.5: espectro uniforme na banda, sem picos (imagem física típica)
        // cv = 1.5: CV moderado — limiar de ativação do indicador
        // cv > 3.0: espectro com picos acentuados → Moiré forte (score = 1.0)
        return ((cv - 0.5f) / 2.5f).coerceIn(0f, 1f)
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
    // Análise 2 — Superfície emissiva (substituiu detectSpecularReflection em v1.6)
    // ---------------------------------------------------------------------------

    /**
     * Detecta superfície emissiva (tela) por análise de uniformidade de luminância.
     *
     * Telas digitais emitem luz própria e produzem luminância média elevada com variância
     * local baixa — iluminação uniforme sem gradientes naturais de luz ambiente.
     * Objetos físicos iluminados por luz natural têm gradientes pronunciados e variância
     * local alta mesmo com luminância média similar.
     *
     * Sinal: combinação de luminância média elevada (> 60) + baixa variância local.
     * Score alto quando meanLuminance > 60 AND variância local média < 1200.
     *
     * CALIBRAÇÃO NECESSÁRIA: limiares definidos com base no dataset atual (482 imagens).
     * Validar com dataset expandido — especialmente telas OLED escuras (meanLum baixo).
     *
     * @param yPlane Plano Y extraído com strideOther.
     * @param width  Largura efetiva do plano.
     * @param height Altura efetiva do plano.
     * @return Score 0.0–1.0 (1.0 = superfície emissiva com alta confiança).
     */
    private fun detectEmissiveSurface(yPlane: ByteArray, width: Int, height: Int): Float {
        val meanLum = yPlane.map { it.toInt() and 0xFF }.average().toFloat()

        // Luminância muito baixa: sem luz suficiente para distinguir tela de objeto
        if (meanLum < 60f) return 0f

        val windowSize = 8
        var totalLocalVariance = 0.0
        var windowCount = 0

        var row = 0
        while (row + windowSize <= height) {
            var col = 0
            while (col + windowSize <= width) {
                val n = windowSize * windowSize
                var sum = 0.0
                var sumSq = 0.0

                for (wr in 0 until windowSize) {
                    for (wc in 0 until windowSize) {
                        val idx = (row + wr) * width + (col + wc)
                        val y = (yPlane[idx].toInt() and 0xFF).toDouble()
                        sum += y
                        sumSq += y * y
                    }
                }
                val variance = (sumSq / n) - (sum / n) * (sum / n)
                totalLocalVariance += variance
                windowCount++
                col += windowSize
            }
            row += windowSize
        }

        if (windowCount == 0) return 0f

        val meanLocalVariance = totalLocalVariance / windowCount

        // Telas emissivas: meanLum alto + variância local baixa
        // Objetos físicos: variância local alta independente da luminância
        val lumScore = ((meanLum - 60f) / (180f - 60f)).coerceIn(0f, 1f)
        val varScore = (1f - (meanLocalVariance / 1200.0).coerceIn(0.0, 1.0)).toFloat()

        // Produto: penaliza quando um dos sinais é fraco — ambos precisam estar presentes
        return (lumScore * varScore).coerceIn(0f, 1f)
    }

    // DESATIVADO v1.6 — substituído por detectEmissiveSurface. Ver DT-004.
    // O indicador original buscava reflexo especular concentrado (0.5–8% de pixels > 240).
    // Problema: imagens recapturadas de telas emissivas não produzem reflexo especular —
    // a tela é fonte de luz, não superfície reflexiva. Score era zero em todas as 482 amostras.
    //
    // private fun detectSpecularReflection(yPlane: ByteArray, width: Int, height: Int): Float {
    //     val totalPixels = width * height
    //     var brightCount = 0
    //     for (byte in yPlane) {
    //         if ((byte.toInt() and 0xFF) > 240) brightCount++
    //     }
    //     val brightRatio = brightCount.toFloat() / totalPixels
    //     val minRatio    = 0.005f
    //     val maxRatio    = 0.15f
    //     if (brightRatio < minRatio || brightRatio > maxRatio) return 0f
    //     val center = (minRatio + maxRatio) / 2f
    //     val range  = (maxRatio - minRatio) / 2f
    //     val dist   = abs(brightRatio - center) / range
    //     return (1f - dist).coerceIn(0f, 1f)
    // }

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
     * Score alto se a variância média excede o limiar empírico (~30.0 para objetos físicos).
     *
     * @param uPlane Plano de crominância U (Cb), extraído com strideOther.
     * @param vPlane Plano de crominância V (Cr), extraído com strideOther.
     * @param halfW  Largura dos planos U/V após subamostragem.
     * @param halfH  Altura dos planos U/V após subamostragem.
     * @return Score 0.0–1.0 (1.0 = padrão cromático de tela forte).
     */
    private fun detectScreenChromaticPattern(
        uPlane: ByteArray,
        vPlane: ByteArray,
        halfW:  Int,
        halfH:  Int
    ): Float {
        val windowSize    = 4
        var totalVariance = 0.0
        var windowCount   = 0

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

                val varU = (sumSqU / n) - (sumU / n) * (sumU / n)
                val varV = (sumSqV / n) - (sumV / n) * (sumV / n)
                totalVariance += (varU + varV) / 2.0
                windowCount++

                col += windowSize
            }
            row += windowSize
        }

        if (windowCount == 0) return 0f

        val meanVariance = totalVariance / windowCount
        // Limiares recalibrados para UV sem stride adicional (DT-004).
        // CALIBRAÇÃO NECESSÁRIA: validar com dataset expandido após aplicar a correção.
        val threshold    = 5.0    // variância mínima esperada em objetos físicos
        val maxVariance  = 40.0   // variância esperada em telas de alta resolução

        return ((meanVariance - threshold) / (maxVariance - threshold))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------
    // Análise 4 — Nitidez de bordas (edge sharpness)
    // ---------------------------------------------------------------------------

    /**
     * Detecta dominância de bordas abruptas — assinatura de telas digitais.
     *
     * Telas digitais exibem texto, ícones e elementos gráficos com transições de intensidade
     * de 0 a 255 em um único pixel. Objetos físicos iluminados por luz contínua apresentam
     * gradientes suaves mesmo em bordas reais (sombras, profundidade de campo, textura).
     *
     * Algoritmo:
     * 1. Reamostra o plano Y para [FFT_SIZE]×[FFT_SIZE] (grade compartilhada com Moiré).
     * 2. Calcula o gradiente de magnitude por diferenças centrais:
     *    mag = (|Δx| + |Δy|) / 2, com Δx = pixel[col+1] − pixel[col−1].
     * 3. Computa a razão de bordas muito nítidas (mag ≥ [EDGE_SHARP_THRESHOLD])
     *    em relação a todas as bordas reais (mag ≥ [EDGE_SOFT_THRESHOLD]).
     * 4. Score alto quando bordas nítidas dominam — comportamento de tela.
     *
     * Limiares empíricos (faixa 0–255):
     * - [EDGE_SOFT_THRESHOLD] = 15: separa bordas reais de ruído de sensor.
     * - [EDGE_SHARP_THRESHOLD] = 70: ~27% de contraste entre pixels adjacentes —
     *   típico de borda de glifo de texto em tela; raro em objetos físicos.
     *
     * Normalização: sharpRatio < 0.3 → score = 0 (objeto físico); > 0.6 → score = 1.0 (tela).
     *
     * @param yPlane Plano Y extraído com strideMoire (alta resolução).
     * @param width  Largura efetiva do plano.
     * @param height Altura efetiva do plano.
     * @return Score 0.0–1.0 (1.0 = bordas abruptas dominantes, perfil de tela).
     */
    private fun detectEdgeSharpness(yPlane: ByteArray, width: Int, height: Int): Float {
        // Reamostra para FFT_SIZE × FFT_SIZE — mesma grade da análise de Moiré
        val sampled = Array(FFT_SIZE) { row ->
            IntArray(FFT_SIZE) { col ->
                val srcRow = (row.toLong() * height / FFT_SIZE).toInt()
                val srcCol = (col.toLong() * width  / FFT_SIZE).toInt()
                yPlane[srcRow * width + srcCol].toInt() and 0xFF
            }
        }

        var sharpCount = 0   // bordas com gradiente >= EDGE_SHARP_THRESHOLD
        var softCount  = 0   // bordas com gradiente em [EDGE_SOFT, EDGE_SHARP)

        // Diferenças centrais — exclui borda da grade (1 pixel em cada lado)
        for (row in 1 until FFT_SIZE - 1) {
            for (col in 1 until FFT_SIZE - 1) {
                val gx  = abs(sampled[row][col + 1] - sampled[row][col - 1])
                val gy  = abs(sampled[row + 1][col] - sampled[row - 1][col])
                val mag = (gx + gy) / 2
                when {
                    mag >= EDGE_SHARP_THRESHOLD -> sharpCount++
                    mag >= EDGE_SOFT_THRESHOLD  -> softCount++
                }
            }
        }

        val totalEdges = sharpCount + softCount
        if (totalEdges == 0) return 0f

        // sharpRatio alto → tela (muitas bordas abruptas)
        // sharpRatio baixo → objeto físico (bordas suaves dominam)
        val sharpRatio    = sharpCount.toFloat() / totalEdges
        val baseRatio     = 0.2f   // reduzido de 0.3 — objetos físicos podem ter até 20% de bordas nítidas
        val saturation    = 0.5f   // reduzido de 0.6 — telas atingem saturação com 50% de bordas nítidas

        return ((sharpRatio - baseRatio) / (saturation - baseRatio))
            .coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------
    // Utilitários internos
    // ---------------------------------------------------------------------------

    /**
     * Extrai os bytes efetivos de um plano YUV descartando padding de linha e pixelStride.
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

    /**
     * Extrai os bytes de um plano YUV com subamostragem por [stride].
     *
     * Quando stride == 1, delega para [extractPlane] sem overhead.
     * Quando stride == 2, lê 1 pixel a cada 2 em cada dimensão, produzindo
     * (width/stride)×(height/stride) bytes — ~4× menos trabalho para analyses
     * que toleram resolução reduzida.
     *
     * @param plane  Plano de cor do frame.
     * @param width  Largura original do plano em pixels.
     * @param height Altura original do plano em pixels.
     * @param stride Passo de subamostragem (1 = sem subamostragem, 2 = metade).
     * @return ByteArray compacto com (width/stride)×(height/stride) bytes.
     */
    private fun extractPlaneStrided(
        plane:  ImageProxy.PlaneProxy,
        width:  Int,
        height: Int,
        stride: Int
    ): ByteArray {
        if (stride == 1) return extractPlane(plane, width, height)

        val buffer      = plane.buffer
        val rowStride   = plane.rowStride
        val pixelStride = plane.pixelStride
        val outW        = width  / stride
        val outH        = height / stride
        val output      = ByteArray(outW * outH)
        var idx         = 0

        for (row in 0 until outH) {
            val srcRow   = row * stride
            val rowStart = srcRow * rowStride
            for (col in 0 until outW) {
                val srcCol = col * stride
                output[idx++] = buffer.get(rowStart + srcCol * pixelStride)
            }
        }

        return output
    }
}
