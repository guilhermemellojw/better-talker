package com.bettertalker.app.data.ai

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Estado do download do modelo Gemma (`.litertlm`). */
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

/** Teto de 3 GiB (Gemma ~2,59 GB); total desconhecido passa. Puro/testável. */
fun modelSizeAllowed(totalBytes: Long): Boolean =
    totalBytes < 0 || totalBytes <= LlmConfig.MAX_MODEL_BYTES

/** Espaço livre mínimo no volume externo do app (modelo + margem). Puro/testável. */
const val MODEL_MIN_FREE_BYTES: Long = 3_200_000_000L

/** Espaço livre suficiente para baixar o modelo com segurança. Puro/testável. */
fun hasEnoughSpace(usableBytes: Long): Boolean = usableBytes >= MODEL_MIN_FREE_BYTES

/** SHA-256 de um arquivo local, em hex minúsculo. Puro/testável. */
fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Confere o hex esperado (case-insensitive). Hex vazio nunca confere. Puro/testável. */
fun verifySha256(file: File, expectedHex: String): Boolean =
    expectedHex.isNotBlank() && sha256Hex(file).equals(expectedHex, ignoreCase = true)

/**
 * Candidatos a arquivo do modelo, na ordem: interno (adb push/legado), externo
 * (destino do download) e `.part` (download em andamento). Puro/testável.
 */
fun modelFileCandidates(
    filesDir: File,
    externalDir: File?,
    fileName: String = LlmModelConfig.FILE_NAME,
): List<File> = listOfNotNull(
    File(File(filesDir, "models"), fileName),
    externalDir?.let { File(it, fileName) },
    externalDir?.let { File(it, "$fileName.part") },
)

/**
 * T2 — baixa o `.litertlm` do Gemma via DownloadManager (só Wi-Fi) **direto**
 * para o diretório externo privado do app (`getExternalFilesDir("models")`),
 * o mesmo que `LitertGemmaEngine.modelFile()` já lê — sem cópia (pico 1×).
 *
 * Fluxo: `gemma-4-E2B-it.litertlm.part` → verificação SHA-256 → rename atômico
 * para o nome final. Download em andamento sobrevive à morte do app; `start()`
 * adota o registro ativo pelo título (resume do DownloadManager). Cancelar
 * remove o download do sistema e o `.part`. O aceite de licença é passo de UI.
 */
class ModelDownloadManager(private val ctx: Context) {
    private val _state = MutableStateFlow<ModelDlState>(ModelDlState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var dmId: Long? = null

    fun externalModelsDir(): File? = ctx.getExternalFilesDir("models")

    fun internalFile(): File =
        File(File(ctx.filesDir, "models").apply { mkdirs() }, LlmModelConfig.FILE_NAME)

    fun externalFile(): File? = externalModelsDir()?.let { File(it, LlmModelConfig.FILE_NAME) }

    /** Arquivo final que o engine lê: externo (download) se presente; senão interno (adb push). */
    fun modelFile(): File {
        val ext = externalFile()
        return if (ext != null && ext.isFile) ext else internalFile()
    }

    fun isReady(): Boolean = modelFile().isFile && modelFile().length() > 0

    fun cancel() {
        job?.cancel()
        job = null
        dmId?.let {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            runCatching { dm.remove(it) }
        }
        dmId = null
        _state.value = ModelDlState.Idle
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        cancel()
        modelFileCandidates(ctx.filesDir, externalModelsDir()).forEach { f ->
            runCatching { f.delete() }
        }
        _state.value = ModelDlState.Idle
    }

    /**
     * Inicia (ou adota) o download. Retorna imediatamente; acompanhe [state].
     * [sha256Hex] vazio pula a verificação (não recomendado).
     */
    fun start(
        scope: kotlinx.coroutines.CoroutineScope,
        url: String,
        sha256Hex: String,
        expectedBytes: Long = -1L,
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
                    _state.value = ModelDlState.Failed("Modelo acima do teto de 3 GB.")
                    return@launch
                }
                val extDir = externalModelsDir()
                if (extDir == null) {
                    _state.value = ModelDlState.Failed("Armazenamento externo indisponível.")
                    return@launch
                }
                if (!hasEnoughSpace(extDir.usableSpace)) {
                    _state.value = ModelDlState.Failed("Sem espaço livre (mínimo 3,2 GB).")
                    return@launch
                }
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val part = File(extDir, "${LlmModelConfig.FILE_NAME}.part")
                val existing = findDownload(dm)
                val adoptCompleted =
                    existing != null && existing.status == DownloadManager.STATUS_SUCCESSFUL && part.isFile
                val id = if (adoptCompleted) {
                    existing!!.id
                } else {
                    existing?.let { runCatching { dm.remove(it.id) } }
                    enqueueNew(dm, url)
                }
                dmId = id
                if (!adoptCompleted && !track(dm, id)) return@launch
                _state.value = ModelDlState.Verifying
                if (!part.isFile) {
                    _state.value = ModelDlState.Failed("Arquivo do download não encontrado. Tente de novo.")
                    return@launch
                }
                if (!verifySha256(part, sha256Hex)) {
                    part.delete()
                    _state.value = ModelDlState.Failed("Checksum divergente. Baixe de novo.")
                    return@launch
                }
                val fin = File(extDir, LlmModelConfig.FILE_NAME)
                if (fin.exists()) fin.delete()
                val moved = part.renameTo(fin)
                if (!moved) {
                    part.inputStream().use { ins -> fin.outputStream().use { out -> ins.copyTo(out) } }
                    part.delete()
                }
                runCatching { dm.remove(id) }
                dmId = null
                _state.value = ModelDlState.Ready(fin.absolutePath)
            } catch (e: CancellationException) {
                _state.value = ModelDlState.Idle
                throw e
            } catch (_: Exception) {
                _state.value = ModelDlState.Failed("Falha no download. Tente de novo no Wi-Fi.")
            }
        }
    }

    /** Acompanha até terminar; `false` quando falhou (estado já publicado). */
    private suspend fun track(dm: DownloadManager, id: Long): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            val (status, done, total) = queryDm(dm, id)
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> return true
                DownloadManager.STATUS_FAILED -> {
                    _state.value = ModelDlState.Failed("Download falhou. Tente de novo no Wi-Fi.")
                    return false
                }
                else -> {
                    if (total > 0 && !modelSizeAllowed(total)) {
                        runCatching { dm.remove(id) }
                        _state.value = ModelDlState.Failed("Modelo acima do teto de 3 GB.")
                        return false
                    }
                    _state.value = ModelDlState.Downloading(done, total)
                    delay(1000)
                }
            }
        }
    }

    private fun enqueueNew(dm: DownloadManager, url: String): Long =
        dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setAllowedOverRoaming(false)
                .setTitle(TITLE)
                .setDescription("Download único (${LlmModelConfig.DISPLAY_SIZE}), só no Wi-Fi")
                .setDestinationInExternalFilesDir(ctx, null, "models/${LlmModelConfig.FILE_NAME}.part")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
        )

    private data class DmRef(val id: Long, val status: Int)

    /** Registro do próprio app (pelo título): em andamento preferido; senão concluído. */
    private fun findDownload(dm: DownloadManager): DmRef? = runCatching {
        dm.query(DownloadManager.Query())?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val stCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            var running: DmRef? = null
            var done: DmRef? = null
            while (c.moveToNext()) {
                if (c.getString(titleCol) != TITLE) continue
                val st = c.getInt(stCol)
                val id = c.getLong(idCol)
                when (st) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING ->
                        if (running == null) running = DmRef(id, st)
                    DownloadManager.STATUS_SUCCESSFUL ->
                        if (done == null) done = DmRef(id, st)
                }
            }
            running ?: done
        }
    }.getOrNull()

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

    companion object {
        const val TITLE = "Better Talker — modelo IA (Gemma)"
    }
}
