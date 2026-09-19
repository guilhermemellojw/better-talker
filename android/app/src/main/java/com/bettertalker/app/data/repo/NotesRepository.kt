package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.FolderEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.data.util.plainFromMarkdown
import kotlinx.coroutines.flow.Flow

class NotesRepository(private val db: AppDatabase) {
    fun observeNotes(folderId: String?, q: String): Flow<List<NoteEntity>> =
        db.noteDao().observe(folderId, q.trim())
    fun observeFolders(): Flow<List<FolderEntity>> = db.folderDao().observe()

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
    }
    suspend fun trash(id: String) = db.noteDao().trash(id, System.currentTimeMillis())
    suspend fun newFolder(name: String, color: Long) {
        db.folderDao().upsert(FolderEntity(newId("fld"), name, color, System.currentTimeMillis()))
    }
    fun attachmentsFor(noteId: String): Flow<List<AttachmentEntity>> =
        db.attachmentDao().observeForNote(noteId)
}
