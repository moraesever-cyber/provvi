package br.com.provvi.diagnostic

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiagnosticResult(
    val filename:       String,
    val label:          String,   // "real", "recapture" ou "unknown" — inferido do nome
    val scoreMoire:     Float,
    val scoreSpecular:  Float,
    val scoreChromatic: Float,
    val scoreEdge:      Float,
    val scoreCombined:  Float,
    val meanLuminance:  Float,
    val verdict:        String,   // "NONE", "MEDIUM", "BLOCK", "INCONCLUSIVE"
    val widthPx:        Int,
    val heightPx:       Int
)

object DiagnosticRunner {

    /**
     * Processa todas as imagens de uma pasta selecionada pelo usuário via SAF.
     *
     * Convenção de label: inferido do nome do arquivo ou da pasta-pai.
     * - "real"      → contém "real"
     * - "recapture" → contém "recapture", "screen", "tela" ou "foto"
     * - "unknown"   → demais casos
     *
     * @param context     Context para acessar o ContentResolver.
     * @param folderUri   URI da pasta selecionada via Intent ACTION_OPEN_DOCUMENT_TREE.
     * @param onProgress  Callback chamado a cada imagem processada (índice, total, filename).
     * @return Lista de [DiagnosticResult] na ordem de processamento.
     */
    suspend fun runBatch(
        context:    Context,
        folderUri:  Uri,
        onProgress: (Int, Int, String) -> Unit
    ): List<DiagnosticResult> = withContext(Dispatchers.Default) {

        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext emptyList()

        val imageFiles = folder.listFiles()
            .filter { it.isFile && it.type?.startsWith("image/") == true }
            .sortedBy { it.name }

        val results = mutableListOf<DiagnosticResult>()
        val total   = imageFiles.size

        imageFiles.forEachIndexed { index, docFile ->
            val filename = docFile.name ?: "unknown_$index"
            onProgress(index + 1, total, filename)

            try {
                val inputStream = context.contentResolver.openInputStream(docFile.uri)
                    ?: return@forEachIndexed
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bitmap == null) return@forEachIndexed

                val fakeProxy   = BitmapImageProxyWrapper(bitmap)
                val diagnostic  = RecaptureDetector.analyzeWithScores(fakeProxy)
                val label       = inferLabel(filename, folder.name ?: "")

                results.add(DiagnosticResult(
                    filename       = filename,
                    label          = label,
                    scoreMoire     = diagnostic.scoreMoire,
                    scoreSpecular  = diagnostic.scoreSpecular,
                    scoreChromatic = diagnostic.scoreChromatic,
                    scoreEdge      = diagnostic.scoreEdge,
                    scoreCombined  = diagnostic.scoreCombined,
                    meanLuminance  = diagnostic.meanLuminance,
                    verdict        = diagnostic.verdict,
                    widthPx        = bitmap.width,
                    heightPx       = bitmap.height
                ))

                bitmap.recycle()

            } catch (e: Exception) {
                android.util.Log.e("DiagnosticRunner", "Erro em $filename: ${e.message}")
            }
        }

        results
    }

    /**
     * Exporta os resultados como CSV para o diretório de Downloads do dispositivo.
     * Em Android 10+ (API 29+) usa MediaStore para gravar sem permissão de armazenamento.
     * Em API <= 28 grava diretamente via File (requer WRITE_EXTERNAL_STORAGE).
     *
     * @return Caminho/URI do arquivo gerado como string.
     */
    suspend fun exportCsv(
        context: Context,
        results: List<DiagnosticResult>
    ): String = withContext(Dispatchers.IO) {

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val filename  = "recapture_diagnostic_$timestamp.csv"

        val csvContent = buildString {
            appendLine("filename,label,score_moire,score_specular,score_chromatic," +
                       "score_edge,score_combined,mean_luminance,verdict,width_px,height_px")
            results.forEach { r ->
                appendLine("${r.filename},${r.label}," +
                    "${"%.4f".format(r.scoreMoire)}," +
                    "${"%.4f".format(r.scoreSpecular)}," +
                    "${"%.4f".format(r.scoreChromatic)}," +
                    "${"%.4f".format(r.scoreEdge)}," +
                    "${"%.4f".format(r.scoreCombined)}," +
                    "${"%.1f".format(r.meanLuminance)}," +
                    "${r.verdict},${r.widthPx},${r.heightPx}")
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // API 29+ — MediaStore, sem permissão de armazenamento
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext "Erro: não foi possível criar arquivo"

            context.contentResolver.openOutputStream(uri)?.use { it.write(csvContent.toByteArray()) }
            "Downloads/$filename"

        } else {
            // API <= 28 — File direto
            val file = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                filename
            )
            file.writeText(csvContent)
            file.absolutePath
        }
    }

    private fun inferLabel(filename: String, folderName: String): String {
        val combined = (filename + folderName).lowercase()
        return when {
            "real" in combined                                               -> "real"
            "recapture" in combined || "screen" in combined
                || "tela" in combined || "foto" in combined                  -> "recapture"
            else                                                             -> "unknown"
        }
    }
}
