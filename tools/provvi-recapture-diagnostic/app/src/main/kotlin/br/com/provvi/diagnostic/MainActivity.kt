package br.com.provvi.diagnostic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog:           TextView
    private lateinit var btnSelectFolder: Button
    private lateinit var btnExportCsv:    Button

    private var lastResults: List<DiagnosticResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog           = findViewById(R.id.tvLog)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnExportCsv    = findViewById(R.id.btnExportCsv)
        btnExportCsv.isEnabled = false

        btnSelectFolder.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                REQUEST_FOLDER
            )
        }

        btnExportCsv.setOnClickListener { exportResults() }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == Activity.RESULT_OK) {
            data?.data?.let { processFolder(it) }
        }
    }

    private fun processFolder(uri: android.net.Uri) {
        tvLog.text = "Processando...\n"
        btnSelectFolder.isEnabled = false
        btnExportCsv.isEnabled    = false

        lifecycleScope.launch {
            lastResults = DiagnosticRunner.runBatch(
                context    = this@MainActivity,
                folderUri  = uri,
                onProgress = { idx, total, filename ->
                    runOnUiThread {
                        tvLog.append("[$idx/$total] $filename\n")
                        findViewById<ScrollView>(R.id.scrollView).fullScroll(ScrollView.FOCUS_DOWN)
                    }
                }
            )

            val reals      = lastResults.filter { it.label == "real" }
            val recaptures = lastResults.filter { it.label == "recapture" }

            val summary = buildString {
                append("\n=== RESUMO ===\n")
                append("Total: ${lastResults.size} imagens\n")
                append("  real: ${reals.size} | recapture: ${recaptures.size}\n\n")

                if (recaptures.isNotEmpty()) {
                    append("--- Recaptures ---\n")
                    append("  score_edge médio:     ${"%.3f".format(recaptures.map { it.scoreEdge }.average())}\n")
                    append("  score_moire médio:    ${"%.3f".format(recaptures.map { it.scoreMoire }.average())}\n")
                    append("  score_combined médio: ${"%.3f".format(recaptures.map { it.scoreCombined }.average())}\n")
                    val blocked  = recaptures.count { it.verdict == "BLOCK" }
                    val medium   = recaptures.count { it.verdict == "MEDIUM" }
                    val passaram = recaptures.size - blocked - medium
                    append("  BLOCK: $blocked | MEDIUM: $medium | passaram: $passaram\n\n")
                }

                if (reals.isNotEmpty()) {
                    append("--- Reais ---\n")
                    append("  score_combined médio: ${"%.3f".format(reals.map { it.scoreCombined }.average())}\n")
                    val falsePos = reals.count { it.verdict != "NONE" }
                    append("  falsos positivos: $falsePos/${reals.size}\n")
                }
            }

            runOnUiThread {
                tvLog.append(summary)
                btnSelectFolder.isEnabled = true
                btnExportCsv.isEnabled    = true
            }
        }
    }

    private fun exportResults() {
        lifecycleScope.launch {
            val path = DiagnosticRunner.exportCsv(this@MainActivity, lastResults)
            runOnUiThread { tvLog.append("\nCSV exportado:\n$path\n") }
        }
    }

    companion object {
        private const val REQUEST_FOLDER = 1001
    }
}
