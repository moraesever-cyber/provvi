package br.com.provvi

import android.content.Context
import android.graphics.ImageFormat
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import br.com.provvi.c2pa.C2paEngine
import br.com.provvi.c2pa.C2paResult
import br.com.provvi.crypto.FrameHash
import br.com.provvi.crypto.FrameHasher
import br.com.provvi.location.LocationResult
import br.com.provvi.location.LocationValidator
import br.com.provvi.security.DeviceIntegrityChecker
import br.com.provvi.security.DeviceVerdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer

/**
 * Testes unitários do pipeline de captura autenticada do Provvi SDK.
 *
 * Executados na JVM (não instrumentados), sem emulador ou dispositivo físico.
 * Cada teste isola uma camada do ADR-001 e verifica seu contrato de saída
 * independentemente das demais camadas.
 */
class ProvviCaptureIntegrationTest {

    // -------------------------------------------------------------------------
    // Teste 1 — Camada 3: FrameHasher
    // -------------------------------------------------------------------------

    /**
     * Verifica que dois frames com bytes YUV idênticos produzem hashes SHA-256 iguais
     * e que o hash tem o tamanho correto (64 caracteres hexadecimais = 256 bits).
     */
    @Test
    fun `FrameHasher_computeHash_retornaHashDeterministico`() {
        // Dois proxies independentes com os mesmos bytes YUV determinísticos
        val proxy1 = criarImageProxyMock(width = 4, height = 4, timestamp = 111L)
        val proxy2 = criarImageProxyMock(width = 4, height = 4, timestamp = 111L)

        val hash1 = FrameHasher.computeHash(proxy1)
        val hash2 = FrameHasher.computeHash(proxy2)

        // Determinismo: mesmos bytes → mesmo hash (propriedade fundamental do SHA-256)
        assertEquals("hashes devem ser iguais para bytes YUV idênticos", hash1.rawHashHex, hash2.rawHashHex)

        // Comprimento: SHA-256 = 32 bytes = 64 caracteres hexadecimais
        assertEquals("hash SHA-256 deve ter 64 caracteres hex", 64, hash1.rawHashHex.length)

        // Formato: apenas dígitos hexadecimais minúsculos
        assertTrue(
            "hash deve conter apenas caracteres [0-9a-f]",
            hash1.rawHashHex.matches(Regex("[0-9a-f]{64}"))
        )
    }

    // -------------------------------------------------------------------------
    // Teste 2 — Camada 3: FrameHash.toManifestAssertion
    // -------------------------------------------------------------------------

    /**
     * Verifica que [FrameHash.toManifestAssertion] retorna todos os campos obrigatórios
     * para composição da asserção C2PA de hash de frame.
     */
    @Test
    fun `FrameHash_toManifestAssertion_contemCamposObrigatorios`() {
        val frameHash = FrameHash(
            rawHashHex     = "a".repeat(64),
            timestampNanos = 9_876_543_210L,
            width          = 1920,
            height         = 1080,
            format         = ImageFormat.YUV_420_888
        )

        val assertion = frameHash.toManifestAssertion()

        // Campos exigidos pelo schema de asserção C2PA de hash de conteúdo
        val camposObrigatorios = listOf(
            "alg",            // algoritmo de hash (sha256)
            "hash",           // valor do hash em hex
            "timestamp_nanos", // timestamp do sensor
            "width",          // largura do frame
            "height",         // altura do frame
            "format",         // formato do buffer (YUV_420_888)
            "plane_order"     // ordem de leitura dos planos (Y_U_V)
        )

        camposObrigatorios.forEach { chave ->
            assertTrue("campo '$chave' deve estar presente na asserção", assertion.containsKey(chave))
        }
    }

    // -------------------------------------------------------------------------
    // Teste 3 — Camada 2: DeviceVerdict.toManifestAssertion
    // -------------------------------------------------------------------------

    /**
     * Verifica que [DeviceIntegrityChecker.toManifestAssertion] inclui todos os campos
     * de integridade obrigatórios e a indicação de validação no backend.
     */
    @Test
    fun `DeviceVerdict_toManifestAssertion_contemCamposObrigatorios`() {
        // Context mockado — DeviceIntegrityChecker armazena apenas a referência
        val mockContext = mock<Context>()
        val checker = DeviceIntegrityChecker(mockContext)

        // Veredicto com todos os níveis de integridade confirmados
        val verdict = DeviceVerdict(
            meetsStrongIntegrity = true,
            meetsDeviceIntegrity = true,
            meetsBasicIntegrity  = true,
            tokenBase64          = "token.de.teste.base64"
        )

        val assertion = checker.toManifestAssertion(verdict)

        // Campos exigidos para rastreabilidade no manifesto C2PA
        val camposObrigatorios = listOf(
            "meets_strong_integrity",   // hardware-backed keystore + bootloader bloqueado
            "meets_device_integrity",   // integridade de software confirmada
            "meets_basic_integrity",    // nível mínimo — app não adulterado
            "token_validated_backend"   // indica que o token JWT está no backend
        )

        camposObrigatorios.forEach { chave ->
            assertTrue("campo '$chave' deve estar presente na asserção", assertion.containsKey(chave))
        }
    }

    // -------------------------------------------------------------------------
    // Teste 4 — Camada 3.5: LocationResult.toManifestAssertion
    // -------------------------------------------------------------------------

    /**
     * Verifica que [LocationValidator.toManifestAssertion] propaga corretamente a flag
     * de localização suspeita quando a divergência entre GPS e NETWORK excede 500 m.
     */
    @Test
    fun `LocationResult_toManifestAssertion_flagSuspiciousQuandoDivergenciaAlta`() {
        // Context mockado — getSystemService retorna null, tratado no construtor como LocationManager?
        val mockContext = mock<Context>()
        whenever(mockContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(null)
        val validator = LocationValidator(mockContext)

        // Resultado com divergência de 650 m (acima do limiar de 500 m do ADR-001)
        val result = LocationResult(
            latitude           = -23.5505,
            longitude          = -46.6333,
            accuracyMeters     = 12f,
            isMockLocation     = false,
            locationSuspicious = true,    // divergência > 500 m
            divergenceMeters   = 650f,
            sourcesUsed        = listOf("GPS", "NETWORK")
        )

        val assertion = validator.toManifestAssertion(result)

        // A flag de suspeita deve ser propagada para o manifesto C2PA
        assertEquals(
            "location_suspicious deve ser true quando divergência > 500 m",
            true,
            assertion["location_suspicious"]
        )
    }

    // -------------------------------------------------------------------------
    // Teste 5 — Camada 4: C2paEngine.sign com imagem inválida
    // -------------------------------------------------------------------------

    /**
     * Verifica que [C2paEngine.sign] retorna [C2paResult.Error] para uma imagem JPEG vazia,
     * sem lançar exceção para a camada superior.
     *
     * Ignorado na JVM desktop porque [System.loadLibrary] não consegue carregar
     * `libprovvi_c2pa.so` fora do runtime Android. Para executar este teste,
     * use o task `connectedAndroidTest` em um emulador ou dispositivo físico.
     */
    @Ignore("requer dispositivo Android ou emulador — System.loadLibrary falha em JVM desktop")
    @Test
    fun `C2paEngine_sign_retornaErroComImagemInvalida`() {
        try {
            val engine = C2paEngine()
            // ByteArray(0) = imagem JPEG inválida — o pipeline C2PA não consegue embuti-la
            val result = runBlocking { engine.sign(ByteArray(0), emptyMap()) }
            assertTrue(
                "imagem JPEG vazia deve produzir C2paResult.Error, não panic",
                result is C2paResult.Error
            )
        } catch (e: UnsatisfiedLinkError) {
            // Caminho esperado em JVM desktop; @Ignore já previne este caminho no CI normal
            return
        }
    }

    // -------------------------------------------------------------------------
    // Funções auxiliares privadas
    // -------------------------------------------------------------------------

    /**
     * Cria um [ImageProxy] mock com três planos YUV_420_888 de conteúdo determinístico.
     *
     * Plano Y  : bytes 0..width*height-1 (crescente), rowStride=width, pixelStride=1
     * Plano U  : todos 128, dimensões width/2 × height/2
     * Plano V  : todos 64,  dimensões width/2 × height/2
     *
     * O conteúdo fixo garante que dois proxies criados com os mesmos parâmetros
     * produzam exatamente o mesmo hash SHA-256.
     */
    private fun criarImageProxyMock(width: Int, height: Int, timestamp: Long = 0L): ImageProxy {
        val proxy = mock<ImageProxy>()

        whenever(proxy.width).thenReturn(width)
        whenever(proxy.height).thenReturn(height)
        whenever(proxy.format).thenReturn(ImageFormat.YUV_420_888)

        // ImageInfo mock — timestamp do sensor necessário para FrameHash
        val imageInfo = mock<ImageInfo>()
        whenever(imageInfo.timestamp).thenReturn(timestamp)
        whenever(proxy.imageInfo).thenReturn(imageInfo)

        // Plano Y: um byte por pixel, valor = índice linear (determinístico)
        val yPlane = criarPlaneMock(
            data       = ByteArray(width * height) { i -> i.toByte() },
            rowStride  = width,
            pixelStride = 1
        )

        // Planos U e V: dimensões reduzidas à metade (subamostragem 4:2:0)
        val halfWidth  = width / 2
        val halfHeight = height / 2

        val uPlane = criarPlaneMock(
            data       = ByteArray(halfWidth * halfHeight) { 128.toByte() },
            rowStride  = halfWidth,
            pixelStride = 1
        )
        val vPlane = criarPlaneMock(
            data       = ByteArray(halfWidth * halfHeight) { 64.toByte() },
            rowStride  = halfWidth,
            pixelStride = 1
        )

        whenever(proxy.planes).thenReturn(arrayOf(yPlane, uPlane, vPlane))

        return proxy
    }

    /**
     * Cria um [ImageProxy.PlaneProxy] mock com buffer de bytes e strides configurados.
     *
     * Usa [ByteBuffer.wrap] para que [ByteBuffer.get(index)] retorne os bytes corretos
     * via acesso absoluto — mesmo comportamento do buffer real de câmera.
     */
    private fun criarPlaneMock(
        data: ByteArray,
        rowStride: Int,
        pixelStride: Int
    ): ImageProxy.PlaneProxy {
        val plane = mock<ImageProxy.PlaneProxy>()
        // ByteBuffer.wrap garante que get(absoluteIndex) retorna data[absoluteIndex]
        whenever(plane.buffer).thenReturn(ByteBuffer.wrap(data))
        whenever(plane.rowStride).thenReturn(rowStride)
        whenever(plane.pixelStride).thenReturn(pixelStride)
        return plane
    }
}
