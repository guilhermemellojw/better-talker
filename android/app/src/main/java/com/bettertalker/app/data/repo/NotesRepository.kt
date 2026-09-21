package com.bettertalker.app.data.repo

import android.content.Context
import com.bettertalker.app.data.cloud.SyncScheduler
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.FolderEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.util.plainFromMarkdown
import kotlinx.coroutines.flow.Flow
import java.io.File

class NotesRepository(private val ctx: Context, private val db: AppDatabase) {
    fun observeNotes(folderId: String?, q: String): Flow<List<NoteEntity>> =
        db.noteDao().observe(folderId, q.trim())
    fun observeFolders(): Flow<List<FolderEntity>> = db.folderDao().observe()
    fun observeTrashed(): Flow<List<NoteEntity>> = db.noteDao().observeTrashed()
    fun observeAllAttachments(): Flow<List<AttachmentEntity>> = db.attachmentDao().observe()

    suspend fun newNote(folderId: String?): String {
        val now = System.currentTimeMillis()
        val id = newId("note")
        db.noteDao().upsert(
            NoteEntity(id, "Nova nota", "", "", folderId, 0xFFFBD44AL, false, false, now, now)
        )
        return id
    }
    suspend fun get(id: String) = db.noteDao().get(id)
    suspend fun save(id: String, title: String, md: String, folderId: String?, color: Long, pinned: Boolean) {
        val cur = db.noteDao().get(id) ?: return
        db.noteDao().upsert(
            cur.copy(title = title.ifBlank { "Sem título" }, mdText = md,
                plainText = plainFromMarkdown(md), folderId = folderId,
                colorArgb = color, pinned = pinned, updatedAt = System.currentTimeMillis())
        )
        SyncScheduler.requestSync(ctx)
    }
    suspend fun trash(id: String) {
        db.noteDao().trash(id, System.currentTimeMillis())
        SyncScheduler.requestSync(ctx)
    }
    suspend fun togglePin(id: String) {
        db.noteDao().togglePin(id, System.currentTimeMillis())
        SyncScheduler.requestSync(ctx)
    }    suspend fun restore(id: String) {
        db.noteDao().restore(id, System.currentTimeMillis())
        SyncScheduler.requestSync(ctx)
    }

    /** Exclusão definitiva: remove a nota + esboço + anexos vinculados (arquivos e trechos). */
    suspend fun deleteForever(id: String) {
        for (a in db.attachmentDao().all().filter { it.noteId == id }) {
            runCatching { File(a.appPath).delete() }
            db.passageDao().deleteForAttachment(a.id)
            db.attachmentDao().delete(a.id)
            db.tombstoneDao().put(TombstoneEntity(a.id, "attachment", System.currentTimeMillis()))
        }
        db.outlineDao().getForNote(id)?.let {
            db.outlineDao().deleteForNote(id)
            db.tombstoneDao().put(TombstoneEntity(it.id, "outline", System.currentTimeMillis()))
        }
        db.noteDao().delete(id)
        db.tombstoneDao().put(TombstoneEntity(id, "note", System.currentTimeMillis()))
        SyncScheduler.requestSync(ctx)
    }

    suspend fun newFolder(name: String, color: Long) {
        db.folderDao().upsert(FolderEntity(newId("fld"), name, color, System.currentTimeMillis()))
        SyncScheduler.requestSync(ctx)
    }
    suspend fun renameFolder(id: String, name: String) {
        db.folderDao().rename(id, name)
        SyncScheduler.requestSync(ctx)
    }
    suspend fun moveNote(id: String, folderId: String?) {
        db.noteDao().move(id, folderId, System.currentTimeMillis())
        SyncScheduler.requestSync(ctx)
    }
    suspend fun deleteFolder(id: String) {
        db.noteDao().clearFolder(id)
        db.folderDao().delete(id)
        db.tombstoneDao().put(TombstoneEntity(id, "folder", System.currentTimeMillis()))
        SyncScheduler.requestSync(ctx)
    }
    fun attachmentsFor(noteId: String): Flow<List<AttachmentEntity>> =
        db.attachmentDao().observeForNote(noteId)
}
