package com.bettertalker.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.matchBaseSlot
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.work.IndexPublicationWorker
import kotlinx.coroutines.flow.Flow
import java.io.File

class LibraryRepository(private val ctx: Context, private val db: AppDatabase) {
    fun observe(): Flow<List<AttachmentEntity>> = db.attachmentDao().observe()
    suspend fun all() = db.attachmentDao().all()

    suspend fun baseReady(slot: String) = db.attachmentDao().baseReady(slot)

    /** Importa via SAF preservando nome/extensão reais. Uso pessoal BYOD. */
    suspend fun importUri(uri: Uri, noteId: String? = null): String? {
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: "arquivo-${System.currentTimeMillis()}.pdf"
        val kind = detectKind(name)
        if (kind == DocKind.UNSUPPORTED) return null
        val tmp = File(ctx.cacheDir, "imp-$name")
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            tmp.outputStream().use { ins.copyTo(it) }
        }
        return importFile(tmp, name, noteId)
    }

    /** Importa arquivo já baixado (Downloads/SAF) para pasta privada do app. */
    suspend fun importFile(src: File, displayName: String = src.name, noteId: String? = null): String? {
        val kind = detectKind(displayName, src)
        if (kind == DocKind.UNSUPPORTED) return null
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
        return id
    }

    suspend fun registerDownloaded(fileName: String, absPath: String, noteId: String? = null): String? {
        // DownloadManager conclui em background: aguarda o arquivo aparecer (até ~60s).
        var tries = 0
        while (!File(absPath).exists() && tries < 120) {
            kotlinx.coroutines.delay(500)
            tries++
        }
        val src = File(absPath)
        if (!src.exists()) {
            // registra mesmo assim para o usuário reindexar depois
            val id = newId("att")
            db.attachmentDao().upsert(
                AttachmentEntity(
                    id, noteId, fileName, "pdf", 0L, absPath,
                    false, System.currentTimeMillis(), matchBaseSlot(fileName),
                    "failed", "Download ainda não concluiu. Toque Reindexar."
                )
            )
            return id
        }
        return importFile(src, fileName, noteId)
    }

    suspend fun delete(id: String) {
        val a = db.attachmentDao().get(id)
        if (a != null) {
            runCatching { File(a.appPath).delete() }
            db.passageDao().deleteForAttachment(id)
            db.attachmentDao().delete(id)
        }
    }

    suspend fun reindex(id: String) {
        db.attachmentDao().setStatus(id, false, "indexing", null)
        enqueueIndex(id)
    }

    suspend fun linkToNote(id: String, noteId: String?) = db.attachmentDao().setNote(id, noteId)

    suspend fun markBaseSlot(id: String, slot: String?) = db.attachmentDao().setBaseSlot(id, slot)

    private fun enqueueIndex(attachmentId: String) {
        WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<IndexPublicationWorker>()
                .setInputData(workDataOf(IndexPublicationWorker.KEY_ID to attachmentId)).build()
        )
    }
}
