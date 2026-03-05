package br.com.provvi.c2pa

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Resultado da operação de assinatura C2PA
sealed class C2paResult {
    // Manifesto C2PA criado e assinado com sucesso — manifestJson contém o JSON completo
    data class Success(val manifestJson: String) : C2paResult()

    // Falha em qualquer etapa do pipeline de assinatura
    data class Error(val message: String) : C2paResult()
}

/**
 * Ponto de acesso Kotlin à camada nativa Rust de assinatura C2PA (Camada 4, ADR-001).
 *
 * A biblioteca nativa é carregada automaticamente ao acessar qualquer membro da classe.
 * A nomenclatura do arquivo .so deve corresponder ao nome do crate Rust: "provvi_c2pa"
 * → libprovvi_c2pa.so (Android adiciona o prefixo "lib" automaticamente).
 */
class C2paEngine {

    companion object {
        init {
            // Carrega a biblioteca nativa compilada para a ABI do dispositivo em execução.
            // O Gradle inclui os .so de jniLibs/ no .aar e o sistema os extrai em runtime.
            System.loadLibrary("provvi_c2pa")
        }
    }

    /**
     * Declaração da função nativa implementada em Rust.
     *
     * Mapeada para `Java_br_com_provvi_c2pa_C2paEngine_createManifest` em lib.rs.
     *
     * @param imageBytes     Bytes da imagem JPEG capturada pelo pipeline de câmera.
     * @param assertionsJson JSON com as asserções consolidadas (hash, GPS, device, custom).
     * @return               Manifesto C2PA em JSON ou `{"error":"..."}` em caso de falha.
     */
    external fun createManifest(imageBytes: ByteArray, assertionsJson: String): String

    /**
     * Cria e assina um manifesto C2PA para a imagem fornecida.
     *
     * Executa no dispatcher IO para não bloquear a thread principal.
     * Converte o mapa de asserções para JSON via [JSONObject] (sem dependência extra),
     * chama a implementação nativa Rust e parseia o resultado.
     *
     * @param imageBytes Bytes JPEG da imagem a ser autenticada.
     * @param assertions Mapa com as asserções das camadas anteriores do pipeline:
     *                   - hash do frame RAW ([br.com.provvi.crypto.FrameHash.toManifestAssertion])
     *                   - localização validada ([br.com.provvi.location.LocationResult])
     *                   - integridade do dispositivo ([br.com.provvi.security.DeviceVerdict])
     * @return [C2paResult.Success] com o manifesto JSON ou [C2paResult.Error] com a causa.
     */
    suspend fun sign(
        imageBytes: ByteArray,
        assertions: Map<String, Any>
    ): C2paResult = withContext(Dispatchers.IO) {

        // Serializa o mapa de asserções para JSON.
        // JSONObject(Map) trata valores primitivos, listas e mapas aninhados recursivamente.
        val assertionsJson = try {
            JSONObject(assertions as Map<*, *>).toString()
        } catch (e: Exception) {
            return@withContext C2paResult.Error(
                "falha ao serializar asserções para JSON: ${e.message}"
            )
        }

        // Chama a implementação nativa — operação bloqueante executada no dispatcher IO
        val resultJson = try {
            createManifest(imageBytes, assertionsJson)
        } catch (e: Exception) {
            return@withContext C2paResult.Error(
                "falha na execução da camada nativa C2PA: ${e.message}"
            )
        }

        // Parseia o retorno: manifesto JSON válido ou objeto de erro {"error":"..."}
        return@withContext try {
            val json = JSONObject(resultJson)
            if (json.has("error")) {
                C2paResult.Error(json.getString("error"))
            } else {
                C2paResult.Success(resultJson)
            }
        } catch (e: Exception) {
            C2paResult.Error("retorno inválido da camada nativa: ${e.message}")
        }
    }
}
