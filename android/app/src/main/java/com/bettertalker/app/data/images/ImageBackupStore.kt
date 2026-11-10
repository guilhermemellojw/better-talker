package com.bettertalker.app.data.images

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.ImageBackupEntity
import com.bettertalker.app.data.db.NoteImageEntity

/** Porta de persistência do backup; testes usam um fake em memória. */
interface ImageBackupStore {
    suspend fun image(imageId: String): NoteImageEntity?
    suspend fun saveImage(image: NoteImageEntity)
    suspend fun noteExists(noteId: String): Boolean
    suspend fun sectionExists(sectionId: String): Boolean
    suspend fun backup(imageId: String): ImageBackupEntity?
    suspend fun save(backup: ImageBackupEntity)
    suspend fun deleteBackup(imageId: String)
    suspend fun imagesForNote(noteId: String): List<NoteImageEntity>
}

class RoomImageBackupStore(private val db: AppDatabase) : ImageBackupStore {
    override suspend fun image(imageId: String): NoteImageEntity? =
        db.noteImageDao().get(imageId)

    override suspend fun saveImage(image: NoteImageEntity) =
        db.noteImageDao().upsert(image)

    override suspend fun noteExists(noteId: String): Boolean =
        db.noteDao().get(noteId) != null

    override suspend fun sectionExists(sectionId: String): Boolean =
        db.speechSectionDao().get(sectionId) != null

    override suspend fun backup(imageId: String): ImageBackupEntity? =
        db.imageBackupDao().get(imageId)

    override suspend fun save(backup: ImageBackupEntity) =
        db.imageBackupDao().upsert(backup)

    override suspend fun deleteBackup(imageId: String) =
        db.imageBackupDao().delete(imageId)

    override suspend fun imagesForNote(noteId: String): List<NoteImageEntity> =
        db.noteImageDao().forNote(noteId)
}
