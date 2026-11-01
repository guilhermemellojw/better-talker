package com.bettertalker.app.data.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Download do modelo STT (sherpa-onnx NeMo CTC pt-BR int8).
 *
 * Modelo: ~98,5 MB comprimido (tar.bz2), ~125 MB extraído.
 * Destino: filesDir/stt/ (app-private).
 * Licença: CC-BY-NC-4.0 (não comercial).
 */
class SttModelDownloader(private val context: Context) {

    private val tag = "SttModelDownloader"
    private val modelDir = File(context.filesDir, "stt")
    private val modelFile = File(modelDir, "model.int8.onnx")
    private val tokensFile = File(modelDir, "tokens.txt")

    /** URL do modelo (GitHub releases do sherpa-onnx). */
    val modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
        "sherpa-onnx-nemo-stt_pt_fastconformer_hybrid_large_pc-int8.tar.bz2"

    /** Verifica se o modelo está disponível. */
    fun isModelReady(): Boolean = modelFile.exists() && tokensFile.exists() && modelFile.length() > 0

    /** Tamanho do modelo em disco (bytes). */
    fun modelSizeBytes(): Long = if (modelFile.exists()) modelFile.length() else 0L

    /**
     * Baixa e extrai o modelo.
     * @param onProgress callback (0..100)
     * @param onComplete callback (success, error message)
     */
    suspend fun download(
        onProgress: (Int) -> Unit = {},
        onComplete: (Boolean, String?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        try {
            modelDir.mkdirs()
            val tmpFile = File(context.cacheDir, "stt-model.tar.bz2")
            try {
                // 1. Download
                val url = URL(modelUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.connect()

                val totalSize = conn.contentLength.toLong()
                var downloaded = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = input.read(buffer)
                        while (bytes >= 0) {
                            output.write(buffer, 0, bytes)
                            downloaded += bytes
                            if (totalSize > 0) {
                                onProgress((downloaded * 100 / totalSize).toInt())
                            }
                            bytes = input.read(buffer)
                        }
                    }
                }
                conn.disconnect()

                // 2. Extract (tar.bz2 → model.int8.onnx + tokens.txt)
                extractTarBz2(tmpFile, modelDir)

                // 3. Verify
                if (!modelFile.exists() || !tokensFile.exists()) {
                    throw Exception("Extração incompleta: arquivos não encontrados")
                }

                tmpFile.delete()
                Log.i(tag, "Model downloaded: ${modelFile.length()} bytes")
                onComplete(true, null)
            } catch (e: Exception) {
                tmpFile.delete()
                Log.e(tag, "Download failed: ${e.message}")
                onComplete(false, e.message)
            }
        } catch (e: Exception) {
            Log.e(tag, "Download error: ${e.message}")
            onComplete(false, e.message)
        }
    }

    /** Remove o modelo do disco. */
    fun removeModel() {
        modelDir.deleteRecursively()
        Log.i(tag, "Model removed")
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        // Use Apache Commons Compress if available, or Java's built-in
        // For now, use a simple approach with tar command
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("tar", "-xjf", archive.absolutePath, "-C", destDir.absolutePath)
            )
            process.waitFor()
            // The archive may extract to a subdirectory; move files up
            destDir.walkTopDown().forEach { f ->
                if (f.isFile && (f.name == "model.int8.onnx" || f.name == "tokens.txt")) {
                    if (f.parentFile != destDir) {
                        f.copyTo(File(destDir, f.name), overwrite = true)
                        f.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Extract failed: ${e.message}")
            throw e
        }
    }
}
