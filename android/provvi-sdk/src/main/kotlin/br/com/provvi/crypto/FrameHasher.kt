package br.com.provvi.crypto

import androidx.camera.core.ImageProxy
import java.security.MessageDigest

/**
 * Representa o hash SHA-256 de um frame RAW capturado antes de qualquer compressão.
 *
 * @param rawHashHex    Hash SHA-256 em hexadecimal dos planos YUV concatenados (Y → U → V).
 * @param timestampNanos Timestamp do frame em nanosegundos, extraído diretamente do sensor via
 *                       [ImageProxy.imageInfo]. Não é o relógio do sistema — é o timestamp
 *                       de captura registrado pelo hardware da câmera.
 * @param width         Largura do frame em pixels.
 * @param height        Altura do frame em pixels.
 * @param format        Formato do buffer de imagem (deve ser ImageFormat.YUV_420_888).
 */
data class FrameHash(
    val rawHashHex: String,
    val timestampNanos: Long,
    val width: Int,
    val height: Int,
    val format: Int
) {
    /**
     * Retorna um mapa com os campos prontos para serialização como asserção no manifesto C2PA.
     * As chaves seguem o padrão de nomenclatura do schema C2PA para claims de hash de conteúdo.
     */
    fun toManifestAssertion(): Map<String, Any> = mapOf(
        "alg"             to "sha256",
        "hash"            to rawHashHex,
        "timestamp_nanos" to timestampNanos,
        "width"           to width,
        "height"          to height,
        "format"          to format,
        "plane_order"     to "Y_U_V"
    )
}

/**
 * Responsável por calcular o hash SHA-256 de frames RAW no formato YUV_420_888.
 *
 * O hash é calculado sobre os bytes brutos dos planos de cor, sem qualquer transformação
 * ou compressão, garantindo que a assinatura C2PA represente fielmente o dado capturado
 * pelo sensor antes de qualquer processamento do codec de imagem.
 */
object FrameHasher {

    /**
     * Calcula o hash SHA-256 do frame RAW representado pelo [imageProxy].
     *
     * Os três planos YUV são lidos na ordem Y → U → V e concatenados em um único
     * array de bytes antes do cálculo do hash. Essa ordem é determinística e deve
     * ser mantida para que a verificação posterior produza o mesmo resultado.
     *
     * Importante: esta função NÃO fecha o [imageProxy]. O chamador é responsável
     * por invocar [ImageProxy.close] após o uso do [FrameHash] retornado.
     *
     * @param imageProxy Frame RAW obtido do pipeline de câmera. Deve estar no formato
     *                   ImageFormat.YUV_420_888 (3 planos).
     * @return [FrameHash] contendo o hash hex, timestamp e metadados do frame.
     * @throws IllegalArgumentException se o frame não possuir exatamente 3 planos.
     */
    fun computeHash(imageProxy: ImageProxy): FrameHash {
        require(imageProxy.planes.size == 3) {
            "Frame deve ter 3 planos YUV (Y, U, V). Planos encontrados: ${imageProxy.planes.size}"
        }

        // Extrai os bytes de cada plano respeitando o rowStride e pixelStride do buffer.
        // O buffer de um plano pode conter padding entre linhas (rowStride > width),
        // portanto lemos apenas os bytes efetivos de cada linha para evitar incluir
        // bytes de preenchimento no hash.
        val planeY = extractPlaneBytes(imageProxy.planes[0], imageProxy.width, imageProxy.height)
        val planeU = extractPlaneBytes(imageProxy.planes[1], imageProxy.width / 2, imageProxy.height / 2)
        val planeV = extractPlaneBytes(imageProxy.planes[2], imageProxy.width / 2, imageProxy.height / 2)

        // Concatena os planos na ordem canônica Y → U → V para o cálculo do hash
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(planeY)
        digest.update(planeU)
        digest.update(planeV)
        val hashBytes = digest.digest()

        return FrameHash(
            rawHashHex    = hashBytes.toHexString(),
            timestampNanos = imageProxy.imageInfo.timestamp,
            width         = imageProxy.width,
            height        = imageProxy.height,
            format        = imageProxy.format
        )
    }

    /**
     * Lê os bytes efetivos de um plano de imagem, descartando o padding de linha.
     *
     * O [ImageProxy.PlaneProxy] pode ter um [ImageProxy.PlaneProxy.getRowStride] maior
     * que a largura real do plano em bytes. Nesse caso, os bytes extras no final de cada
     * linha são padding de alinhamento de memória e não devem ser incluídos no hash.
     *
     * Para o plano Y: cada pixel ocupa 1 byte (pixelStride = 1).
     * Para os planos U e V: podem ser entrelaçados (pixelStride = 2 em NV21/NV12),
     * portanto lemos byte a byte respeitando o pixelStride.
     *
     * @param plane  Plano de cor do frame.
     * @param width  Largura efetiva do plano em pixels.
     * @param height Altura efetiva do plano em pixels.
     * @return Array de bytes contendo apenas os pixels válidos do plano.
     */
    private fun extractPlaneBytes(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int
    ): ByteArray {
        val buffer     = plane.buffer
        val rowStride  = plane.rowStride
        val pixelStride = plane.pixelStride

        // Tamanho final: um byte por pixel no plano (independente do pixelStride do buffer)
        val output = ByteArray(width * height)
        var outputIndex = 0

        // Itera linha a linha para pular o padding de alinhamento ao fim de cada linha
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                // Posição no buffer considerando o espaçamento entre pixels (pixelStride)
                output[outputIndex++] = buffer.get(rowStart + col * pixelStride)
            }
        }

        return output
    }

    /**
     * Converte um array de bytes em uma string hexadecimal minúscula.
     * Usa formatação manual para evitar dependência de bibliotecas externas.
     */
    private fun ByteArray.toHexString(): String {
        val hexChars = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hexChars[i * 2]     = "0123456789abcdef"[v ushr 4]
            hexChars[i * 2 + 1] = "0123456789abcdef"[v and 0x0F]
        }
        return String(hexChars)
    }
}
