package com.bettertalker.app.data.work

import android.app.DownloadManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.repo.LibraryRepository
import kotlinx.coroutines.delay
import java.io.File

/**
 * Reconhece downloads do DownloadManager (sobrevive a rotação/morte do app):
 * aguarda conclusão, copia via ContentResolver para a pasta privada e indexa.
 */
class RegisterDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_DM_ID = "dmId"
        const val KEY_NAME = "fileName"
        const val KEY_PLACEHOLDER = "placeholderId"
        const val KEY_NOTE = "noteId"
    }

    override suspend fun doWork(): Result {
        val dmId = inputData.getLong(KEY_DM_ID, -1L)
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val placeholderId = inputData.getString(KEY_PLACEHOLDER) ?: return Result.failure()
        val noteId = inputData.getString(KEY_NOTE)
        val db = DbProvider.get(applicationContext)
        val repo = LibraryRepository(applicationContext, db)
        val dm = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // aguarda conclusão (até ~10 min)
        var tries = 0
        while (tries < 600) {
            val status = queryStatus(dm, dmId)
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> break
                DownloadManager.STATUS_FAILED -> {
                    repo.failPlaceholder(placeholderId, "Download falhou. Tente de novo.")
                    return Result.success()
                }
                -1 -> {
                    repo.failPlaceholder(placeholderId, "Download não encontrado. Baixe de novo.")
                    return Result.success()
                }
                else -> { delay(1000); tries++ }
            }
        }
        if (tries >= 600) {
            repo.failPlaceholder(placeholderId, "Tempo esgotado aguardando o download.")
            return Result.success()
        }

        return try {
            val uri = dm.getUriForDownloadedFile(dmId)
                ?: run {
                    repo.failPlaceholder(placeholderId, "Arquivo indisponível. Baixe de novo.")
                    return Result.success()
                }
            val dir = File(applicationContext.filesDir, "publicacoes").apply { mkdirs() }
            val tmp = File(dir, "dl-$placeholderId.tmp")
            applicationContext.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > LibraryRepository.MAX_IMPORT_BYTES) {
                            tmp.delete()
                            repo.failPlaceholder(placeholderId, "Arquivo muito grande (limite 150 MB).")
                            return Result.success()
                        }
                        out.write(buf, 0, n)
                    }
                }
            } ?: run {
                repo.failPlaceholder(placeholderId, "Não foi possível ler o download.")
                return Result.success()
            }
            repo.completePlaceholder(placeholderId, tmp, name, noteId)
            tmp.delete()
            Result.success()
        } catch (e: Exception) {
            repo.failPlaceholder(placeholderId, e.message?.take(160) ?: "Falha ao importar.")
            Result.success()
        }
    }

    private fun queryStatus(dm: DownloadManager, id: Long): Int {
        return try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                else -1
            } ?: -1
        } catch (_: Exception) { -1 }
    }
}
