package com.bettertalker.app.data.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * #23-fix-a: download do modelo Piper (voz Jeff) no primeiro uso.
 * Pattern `.part` + rename atômico; progresso via [state]. Se o app fechar
 * no meio, o `.part` fica e o próximo download recomeça do zero (o sherpa
 * não retoma HTTP range nesta versão — reportado).
 */
class PiperModelStore(
    val modelDir: File,
    private val fetch: suspend (url: String, dest: File, onProgress: (Float) -> Unit) -> Unit =
        ::httpFetch,
) {
    data class State(
        val downloaded: Boolean = false,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State(downloaded = ready()))
    val state: StateFlow<State> = _state.asStateFlow()

    /** True se o par mínimo (model.onnx + tokens.txt) está em disco. */
    fun ready(): Boolean =
        File(modelDir, "model.onnx").let { it.exists() && it.length() > 1_000_000 } &&
            File(modelDir, "tokens.txt").let { it.exists() && it.length() > 0 }

    suspend fun download() {
        if (_state.value.downloading) return
        _state.value = State(downloaded = ready(), downloading = true)
        try {
            modelDir.mkdirs()
            val onnxPart = File(modelDir, "model.onnx.part")
            fetch(URL_ONNX, onnxPart) { p -> _state.value = _state.value.copy(progress = p * 0.98f) }
            if (!onnxPart.renameTo(File(modelDir, "model.onnx"))) error("rename do onnx falhou")
            val tokPart = File(modelDir, "tokens.txt.part")
            fetch(URL_TOKENS, tokPart) { p -> _state.value = _state.value.copy(progress = 0.98f + p * 0.02f) }
            if (!tokPart.renameTo(File(modelDir, "tokens.txt"))) error("rename do tokens falhou")
            _state.value = State(downloaded = true)
        } catch (e: CancellationException) {
            _state.value = State(downloaded = ready())
            throw e
        } catch (e: Exception) {
            _state.value = State(downloaded = ready(), error = e.message ?: "Falha no download")
        }
    }

    /** Remove o modelo (para "trocar de voz" no futuro). */
    fun delete() {
        listOf("model.onnx", "tokens.txt", "model.onnx.part", "tokens.txt.part")
            .forEach { runCatching { File(modelDir, it).delete() } }
        _state.value = State(downloaded = ready())
    }

    companion object {
        private const val BASE =
            "https://huggingface.co/csukuangfj/vits-piper-pt_BR-jeff-medium/resolve/main"
        const val URL_ONNX = "$BASE/pt_BR-jeff-medium.onnx"
        const val URL_TOKENS = "$BASE/tokens.txt"

        private suspend fun httpFetch(url: String, dest: File, onProgress: (Float) -> Unit) =
            withContext(Dispatchers.IO) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                try {
                    conn.connect()
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    var read = 0L
                    conn.inputStream.use { input ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
    }
}
