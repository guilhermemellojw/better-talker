package com.bettertalker.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ---------- Entities ----------

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long,
    val createdAt: Long
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mdText: String,
    val plainText: String,
    val folderId: String?,
    val colorArgb: Long,
    val pinned: Boolean,
    val trashed: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val noteId: String?,
    val fileName: String,
    val kind: String, // pdf | epub | docx | rtf | zip | txt
    val sizeBytes: Long,
    val appPath: String,
    val indexed: Boolean,
    val addedAt: Long,
    val baseSlot: String? = null, // be | th | null
    val status: String = "indexing", // indexing | ready | failed
    val error: String? = null
)

@Entity(tableName = "passages")
data class PassageEntity(
    @PrimaryKey val id: String,
    val attachmentId: String,
    val text: String,
    val normalized: String
)

// FTS futuro: busca atual usa LIKE sobre `normalized` (100% offline).
// Mantido fora do @Database para evitar validação do compiler na v1.

// ---------- DAOs ----------

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun observe(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    suspend fun all(): List<FolderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface NoteDao {
    @Query(
        "SELECT * FROM notes WHERE trashed = 0 " +
            "AND (:folderId IS NULL OR folderId = :folderId) " +
            "AND (:q = '' OR title LIKE '%' || :q || '%' OR plainText LIKE '%' || :q || '%') " +
            "ORDER BY pinned DESC, updatedAt DESC"
    )
    fun observe(folderId: String?, q: String): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun get(id: String): NoteEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)
    @Query("UPDATE notes SET trashed = 1, updatedAt = :now WHERE id = :id")
    suspend fun trash(id: String, now: Long)
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM notes WHERE trashed = 0")
    suspend fun count(): Int
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments ORDER BY addedAt DESC")
    fun observe(): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE noteId = :noteId ORDER BY addedAt DESC")
    fun observeForNote(noteId: String): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments ORDER BY addedAt DESC")
    suspend fun all(): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AttachmentEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: AttachmentEntity)
    @Query("UPDATE attachments SET indexed = :indexed WHERE id = :id")
    suspend fun setIndexed(id: String, indexed: Boolean)
    @Query("UPDATE attachments SET indexed = :indexed, status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?)
    @Query("UPDATE attachments SET baseSlot = :slot WHERE id = :id")
    suspend fun setBaseSlot(id: String, slot: String?)
    @Query("UPDATE attachments SET noteId = :noteId WHERE id = :id")
    suspend fun setNote(id: String, noteId: String?)
    @Query("SELECT * FROM attachments WHERE baseSlot = :slot AND indexed = 1 LIMIT 1")
    suspend fun baseReady(slot: String): AttachmentEntity?
    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PassageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PassageEntity>)
    @Query("SELECT * FROM passages WHERE attachmentId = :attachmentId LIMIT 500")
    suspend fun forAttachment(attachmentId: String): List<PassageEntity>
    @Query("DELETE FROM passages WHERE attachmentId = :attachmentId")
    suspend fun deleteForAttachment(attachmentId: String)
    // Busca simples offline (LIKE sobre texto normalizado). FTS usado como índice futuro.
    @Query(
        "SELECT * FROM passages WHERE normalized LIKE '%' || :norm || '%' LIMIT :limit"
    )
    suspend fun searchLike(norm: String, limit: Int): List<PassageEntity>
    // Busca com escopo (base + nota): attachmentId restrito.
    @Query(
        "SELECT * FROM passages WHERE attachmentId IN (:ids) AND normalized LIKE '%' || :norm || '%' LIMIT :limit"
    )
    suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int): List<PassageEntity>
    @Query("SELECT DISTINCT attachmentId FROM passages")
    suspend fun indexedAttachmentIds(): List<String>
}

@Database(
    entities = [FolderEntity::class, NoteEntity::class, AttachmentEntity::class, PassageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun noteDao(): NoteDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun passageDao(): PassageDao
}
