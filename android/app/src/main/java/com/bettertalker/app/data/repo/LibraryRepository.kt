package com.bettertalker.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bettertalker.app.data.cloud.SyncScheduler
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.matchBaseSlot
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.work.IndexPublicationWorker
import com.bettertalker.app.data.work.RegisterDownloadWorker
import kotlinx.coroutines.flow.Flow
import java.io.File

class ImportException(val reason: Reason, msg: String) : Exception(msg) {
    enum class Reason { UNSUPPORTED, TOO_BIG, IO }
}

class LibraryRepository(private val ctx: Context, private val db: AppDatabase) {
    companion object {
        const val MAX_IMPORT_BYTES = 150L * 1024 * 1024
    }

    fun observe(): Flow<List<AttachmentEntity>> = db.attachmentDao().observe()
    suspend fun all() = db.attachmentDao().all()

    suspend fun baseReady(slot: String) = db.attachmentDao().baseReady(slot)

    /** Importa via SAF preservando nome/extensão reais. Uso pessoal BYOD. */
    @Throws(ImportException::class)
    suspend fun importUri(uri: Uri, noteId: String? = null): String {
        val name = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: "arquivo-${System.currentTimeMillis()}.pdf"
        } catch (e: Exception) {
            throw ImportException(ImportException.Reason.IO, "Não foi possível ler o arquivo.")
        }
        val kind = detectKind(name)
        if (kind == DocKind.UNSUPPORTED) {
            throw ImportException(ImportException.Reason.UNSUPPORTED, "Formato não suportado: $name. Use PDF, EPUB, DOCX, RTF, ZIP, TXT ou JWPUB.")
        }
        val tmp = File(ctx.cacheDir, "imp-${System.currentTimeMillis()}-$name")
        try {
            val ins = ctx.contentResolver.openInputStream(uri)
                ?: throw ImportException(ImportException.Reason.IO, "Não foi possível abrir o arquivo.")
            ins.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMPORT_BYTES) {
                            tmp.delete()
                            throw ImportException(ImportException.Reason.TOO_BIG, "Arquivo muito grande (limite 150 MB).")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw ImportException(ImportException.Reason.IO, "Falha ao copiar o arquivo.")
        }
        return importFile(tmp, name, noteId)
            ?: throw ImportException(ImportException.Reason.UNSUPPORTED, "Formato não suportado.")
    }

    /** Importa arquivo já baixado (Downloads/SAF) para pasta privada do app. */
    @Throws(ImportException::class)
    suspend fun importFile(src: File, displayName: String = src.name, noteId: String? = null): String {
        val kind = detectKind(displayName, src)
        if (kind == DocKind.UNSUPPORTED) {
            throw ImportException(ImportException.Reason.UNSUPPORTED, explicitMessage(displayName))
        }
        if (src.length() > MAX_IMPORT_BYTES) {
            throw ImportException(ImportException.Reason.TOO_BIG, "Arquivo muito grande (limite 150 MB).")
        }
        try {
            val dir = File(ctx.filesDir, "publicacoes").apply { mkdirs() }
            val id = newId("att")
            val safe = displayName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").takeLast(80)
            val dst = File(dir, "$id-$safe")
            src.copyTo(dst, overwrite = true)
            val slot = matchBaseSlot(displayName)
            db.attachmentDao().upsert(
                AttachmentEntity(
                    id, noteId, displayName, kind.ext, dst.length(), dst.absolutePath,
                    false, System.currentTimeMillis(), slot, "indexing", null
                )
            )
            enqueueIndex(id)
            SyncScheduler.requestSync(ctx)
            return id
        } catch (e: Exception) {
            throw ImportException(ImportException.Reason.IO, "Falha ao importar o arquivo.")
        }
    }

    private fun explicitMessage(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jwpub" -> "JWPUB ainda não suportado — baixe EPUB ou PDF no site."
            "brl", "bes" -> "Formato $ext ainda não suportado — baixe EPUB ou PDF no site."
            "mp3", "aac", "m4a" -> "Áudio não é indexado — baixe o texto (PDF/EPUB) no site."
            else -> "Formato não suportado: $fileName. Use PDF, EPUB, DOCX, RTF, ZIP, TXT ou JWPUB."
        }
    }

    /** Entrada provisória "Baixando…" criada no momento do enqueue do DownloadManager. */
    suspend fun insertPlaceholder(fileName: String, noteId: String? = null): String? {
        if (detectKind(fileName) == DocKind.UNSUPPORTED) return null
        val id = newId("att")
        db.attachmentDao().upsert(
            AttachmentEntity(
                id, noteId, fileName, detectKind(fileName).ext, 0L, "",
                false, System.currentTimeMillis(), matchBaseSlot(fileName),
                "downloading", null
            )
        )
        return id
    }

    /** Conclui o placeholder com o arquivo copiado e agenda a indexação. */
    suspend fun completePlaceholder(id: String, src: File, displayName: String, noteId: String? = null) {
        val cur = db.attachmentDao().get(id) ?: run {
            importFile(src, displayName, noteId); return
        }
        val kind = detectKind(displayName, src)
        if (kind == DocKind.UNSUPPORTED) {
            db.attachmentDao().update(cur.copy(status = "failed", error = explicitMessage(displayName)))
            return
        }
        try {
            val dir = File(ctx.filesDir, "publicacoes").apply { mkdirs() }
            val safe = displayName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").takeLast(80)
            val dst = File(dir, "$id-$safe")
            src.copyTo(dst, overwrite = true)
            db.attachmentDao().update(
                cur.copy(
                    fileName = displayName, kind = kind.ext, sizeBytes = dst.length(),
                    appPath = dst.absolutePath, indexed = false, status = "indexing",
                    error = null, baseSlot = cur.baseSlot ?: matchBaseSlot(displayName),
                    noteId = noteId ?: cur.noteId
                )
            )
            enqueueIndex(id)
            SyncScheduler.requestSync(ctx)
        } catch (_: Exception) {
            db.attachmentDao().update(cur.copy(status = "failed", error = "Falha ao importar o download."))
        }
    }

    suspend fun failPlaceholder(id: String, msg: String) {
        val cur = db.attachmentDao().get(id) ?: return
        db.attachmentDao().update(cur.copy(status = "failed", error = msg))
    }

    suspend fun delete(id: String) {
        val a = db.attachmentDao().get(id)
        if (a != null) {
            runCatching { File(a.appPath).delete() }
            db.passageDao().deleteForAttachment(id)
            db.attachmentDao().delete(id)
            db.tombstoneDao().put(TombstoneEntity(id, "attachment", System.currentTimeMillis()))
            SyncScheduler.requestSync(ctx)
        }
    }

    suspend fun reindex(id: String) {
        db.attachmentDao().setStatus(id, false, "indexing", null)
        enqueueIndex(id)
        SyncScheduler.requestSync(ctx)
    }

    /** Reindexa todo o acervo uma vez (migração de formato de índice). */
    suspend fun reindexAll() {
        for (a in db.attachmentDao().all()) {
            if (a.status != "downloading") reindex(a.id)
        }
    }

    suspend fun linkToNote(id: String, noteId: String?) {
        db.attachmentDao().setNote(id, noteId)
        SyncScheduler.requestSync(ctx)
    }

    suspend fun markBaseSlot(id: String, slot: String?) {
        db.attachmentDao().setBaseSlot(id, slot)
        SyncScheduler.requestSync(ctx)
    }

    fun enqueueRegisterDownload(dmId: Long, fileName: String, placeholderId: String, noteId: String? = null) {
        val req = OneTimeWorkRequestBuilder<RegisterDownloadWorker>()
            .setInputData(
                workDataOf(
                    RegisterDownloadWorker.KEY_DM_ID to dmId,
                    RegisterDownloadWorker.KEY_NAME to fileName,
                    RegisterDownloadWorker.KEY_PLACEHOLDER to placeholderId,
                    RegisterDownloadWorker.KEY_NOTE to noteId
                )
            ).build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    private fun enqueueIndex(attachmentId: String) {
        WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<IndexPublicationWorker>()
                .setInputData(workDataOf(IndexPublicationWorker.KEY_ID to attachmentId)).build()
        )
    }
}
