package com.bettertalker.app.data.ai

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Estado do download do modelo (.task). */
sealed interface ModelDlState {
    data object Idle : ModelDlState
    data object WaitingWifi : ModelDlState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelDlState
    data object Verifying : ModelDlState
    data class Ready(val path: String) : ModelDlState
    data class Failed(val msg: String) : ModelDlState
}

/** Porção concluída 0..1 (-1 se total desconhecido). Puro/testável. */
fun downloadProgress(doneBytes: Long, totalBytes: Long): Float =
    if (totalBytes <= 0) -1f else (doneBytes.coerceAtMost(totalBytes).toFloat() / totalBytes)

/** Teto de 2 GB (total desconhecido passa; o worker aborta se estourar). Puro/testável. */
fun modelSizeAllowed(totalBytes: Long): Boolean =
    totalBytes < 0 || totalBytes <= LlmConfig.MAX_MODEL_BYTES

/**
 * Baixa o .task via DownloadManager (só Wi-Fi), move para pasta privada,
 * confere SHA-256 e expõe progresso. Independe da indexação de publicações.
 */
class ModelDownloadManager(private val ctx: Context) {
    private val _state = MutableStateFlow<ModelDlState>(ModelDlState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun modelFile(): File = File(File(ctx.filesDir, "models").apply { mkdirs() }, MODEL_FILE)

    fun isReady(): Boolean {
        val f = modelFile()
        return f.exists() && f.length() > 0
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ModelDlState.Idle
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        cancel()
        modelFile().delete()
        _state.value = ModelDlState.Idle
    }

    /**
     * Inicia o download. Retorna imediatamente; acompanhe [state].
     * [sha256Hex] vazio pula a verificação (não recomendado).
     */
    fun start(
        scope: kotlinx.coroutines.CoroutineScope,
        url: String,
        sha256Hex: String,
        expectedBytes: Long = -1L
    ) {
        if (job?.isActive == true) return
        if (isReady()) {
            _state.value = ModelDlState.Ready(modelFile().absolutePath)
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                if (!onWifi()) {
                    _state.value = ModelDlState.WaitingWifi
                    return@launch
                }
                if (expectedBytes > 0 && !modelSizeAllowed(expectedBytes)) {
                    _state.value = ModelDlState.Failed("Modelo acima do teto de 2 GB.")
                    return@launch
                }
                if (ctx.filesDir.usableSpace < MIN_FREE_BYTES) {
                    _state.value = ModelDlState.Failed("Sem espaço livre (mínimo 2,5 GB).")
                    return@launch
                }
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(url))
                    .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                    .setAllowedOverRoaming(false)
                    .setTitle("Better Talker — modelo IA")
                    .setDescription("Download único (~1,6 GB), só no Wi-Fi")
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, TMP_NAME)
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                val dmId = dm.enqueue(req)
                // acompanha (sem limite fixo: 1,6 GB leva tempo)
                while (true) {
                    ensureActive()
                    val (status, done, total) = queryDm(dm, dmId)
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> break
                        DownloadManager.STATUS_FAILED -> {
                            _state.value = ModelDlState.Failed("Download falhou. Tente de novo no Wi-Fi.")
                            return@launch
                        }
                        else -> {
                            if (total > 0 && !modelSizeAllowed(total)) {
                                runCatching { dm.remove(dmId) }
                                _state.value = ModelDlState.Failed("Modelo acima do teto de 2 GB.")
                                return@launch
                            }
                            _state.value = ModelDlState.Downloading(done, total)
                            delay(1000)
                        }
                    }
                }
                _state.value = ModelDlState.Verifying
                val uri = dm.getUriForDownloadedFile(dmId)
                    ?: run {
                        _state.value = ModelDlState.Failed("Arquivo indisponível. Baixe de novo.")
                        return@launch
                    }
                val dst = modelFile()
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    dst.outputStream().use { out -> ins.copyTo(out) }
                } ?: run {
                    _state.value = ModelDlState.Failed("Não foi possível ler o download.")
                    return@launch
                }
                runCatching { dm.remove(dmId) }
                if (sha256Hex.isNotBlank()) {
                    val actual = sha256Of(dst)
                    if (!actual.equals(sha256Hex, ignoreCase = true)) {
                        dst.delete()
                        _state.value = ModelDlState.Failed("Checksum divergente. Baixe de novo.")
                        return@launch
                    }
                }
                _state.value = ModelDlState.Ready(dst.absolutePath)
            } catch (e: CancellationException) {
                _state.value = ModelDlState.Idle
                throw e
            } catch (_: Exception) {
                _state.value = ModelDlState.Failed("Falha no download. Tente de novo no Wi-Fi.")
            }
        }
    }

    private fun onWifi(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun queryDm(dm: DownloadManager, id: Long): Triple<Int, Long, Long> {
        return try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return Triple(-1, 0L, -1L)
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                Triple(st, done, total)
            } ?: Triple(-1, 0L, -1L)
        } catch (_: Exception) {
            Triple(-1, 0L, -1L)
        }
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_FILE = "qwen15-q8.task"
        const val TMP_NAME = "bettertalker-model.tmp"
        const val MIN_FREE_BYTES = 2_500_000_000L
    }
}
