package com.bettertalker.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
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
    val updatedAt: Long,
    /** Verdade visual (cores, highlight, S/T...); mdText é derivado p/ Copilot. */
    val richHtml: String = ""
)

@Entity(tableName = "attachments", indices = [Index("baseSlot")])
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
    val error: String? = null,
    /** DownloadManager id para retentar o registro; -1 = n/a. */
    val downloadId: Long = -1L,
    /** URL de origem (arquivo direto ou página) para baixar de novo. */
    val sourceUrl: String? = null,
    /** Fase 8: trilho (content|training|bible). Backfill via baseSlot na v10. */
    val sourceType: String = "content",
    /** Fase 8: símbolo da publicação (ex: be, th, w); null = desconhecido. */
    val symbol: String? = null
)

@Entity(tableName = "passages", indices = [Index("attachmentId")])
data class PassageEntity(
    @PrimaryKey val id: String,
    val attachmentId: String,
    val text: String,
    val normalized: String,
    val section: String = "",
    /** Fase 8: proveniência (null = ausente, nunca inferir). */
    val ref: String = "",
    val page: Int? = null,
    val paragraph: Int? = null,
    val ord: Int = 0,
    /** Fase 8: categoria serial minúscula; null = classificar on-the-fly. */
    val trainingCategory: String? = null
)

/** Exclusões a propagar para a nuvem (tombstones). */
@Entity(tableName = "tombstones")
data class TombstoneEntity(
    @PrimaryKey val id: String,
    val type: String, // note | folder | attachment | outline
    val deletedAt: Long
)

/** Esboço-base do discurso: um por nota (id determinístico out-<noteId>). */
@Entity(tableName = "outlines")
data class OutlineEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val fileName: String,
    val title: String,
    val totalMinutes: Int?,
    val sectionsJson: String,
    val refsJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long
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
    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)
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
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<NoteEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)
    @Query("UPDATE notes SET trashed = 1, updatedAt = :now WHERE id = :id")
    suspend fun trash(id: String, now: Long)
    @Query("UPDATE notes SET pinned = NOT pinned, updatedAt = :now WHERE id = :id")
    suspend fun togglePin(id: String, now: Long)
    @Query("UPDATE notes SET trashed = 0, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)
    @Query("UPDATE notes SET folderId = :folderId, updatedAt = :now WHERE id = :id")
    suspend fun move(id: String, folderId: String?, now: Long)
    @Query("SELECT * FROM notes WHERE trashed = 1 ORDER BY updatedAt DESC")
    fun observeTrashed(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes")
    suspend fun allIncludingTrashed(): List<NoteEntity>
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)
    @Query("UPDATE notes SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)
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
    @Update
    suspend fun update(a: AttachmentEntity)
    @Query("UPDATE attachments SET indexed = :indexed WHERE id = :id")
    suspend fun setIndexed(id: String, indexed: Boolean)
    @Query("UPDATE attachments SET indexed = :indexed, status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?)
    @Query("UPDATE attachments SET baseSlot = :slot WHERE id = :id")
    suspend fun setBaseSlot(id: String, slot: String?)
    // Fase 8: metadados de trilho/símbolo (indexação classifica).
    @Query("UPDATE attachments SET sourceType = :sourceType, symbol = :symbol WHERE id = :id")
    suspend fun setSourceMeta(id: String, sourceType: String, symbol: String?)
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
    // Fase 8: carga restrita ao escopo (ordenada para determinismo).
    @Query("SELECT * FROM passages WHERE attachmentId IN (:ids) ORDER BY attachmentId ASC, ord ASC")
    suspend fun forAttachments(ids: List<String>): List<PassageEntity>
}

@Database(
    entities = [FolderEntity::class, NoteEntity::class, AttachmentEntity::class, PassageEntity::class, TombstoneEntity::class, OutlineEntity::class, ChatEntity::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun noteDao(): NoteDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun passageDao(): PassageDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun outlineDao(): OutlineDao
    abstract fun chatDao(): ChatDao
}

/** Mensagens do chat com o Copilot (local, por nota; não sincroniza). */
@Entity(tableName = "chat_messages")
data class ChatEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val fromMe: Boolean,
    /** text | ideas | refs | outline_refs */
    val kind: String,
    val payload: String,
    val createdAt: Long
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun observe(noteId: String): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chat_messages WHERE noteId = :noteId ORDER BY createdAt ASC")
    suspend fun all(noteId: String): List<ChatEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(m: ChatEntity)
    @Query("DELETE FROM chat_messages WHERE noteId = :noteId")
    suspend fun clear(noteId: String)
    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TombstoneDao {
    @Query("SELECT * FROM tombstones")
    suspend fun all(): List<TombstoneEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(t: TombstoneEntity)
    @Query("DELETE FROM tombstones WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface OutlineDao {
    @Query("SELECT * FROM outlines WHERE noteId = :noteId LIMIT 1")
    fun observeForNote(noteId: String): Flow<List<OutlineEntity>>
    @Query("SELECT * FROM outlines WHERE noteId = :noteId LIMIT 1")
    suspend fun getForNote(noteId: String): OutlineEntity?
    @Query("SELECT * FROM outlines")
    suspend fun all(): List<OutlineEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(o: OutlineEntity)
    @Query("DELETE FROM outlines WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)
    @Query("DELETE FROM outlines WHERE id = :id")
    suspend fun delete(id: String)
}
