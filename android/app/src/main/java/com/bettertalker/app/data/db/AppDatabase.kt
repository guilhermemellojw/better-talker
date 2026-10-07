package com.bettertalker.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import com.bettertalker.app.domain.speech.DiscourseType
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
    val richHtml: String = "",
    /** Tipo de discurso ([DiscourseType.name]); desconhecido lê como S34_DISCOURSE. */
    val discourseType: String = DiscourseType.S34_DISCOURSE.name
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
    // @Upsert (INSERT; no conflito de unicidade → UPDATE), NUNCA REPLACE: o
    // REPLACE apaga a linha pai e o ON DELETE CASCADE derruba
    // speech_sections/sub_points da nota.
    @Upsert
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
    @Query("SELECT * FROM passages WHERE ref = :ref LIMIT 1")
    suspend fun findByRef(ref: String): PassageEntity?
    // Fase 8: carga restrita ao escopo (ordenada para determinismo).
    @Query("SELECT * FROM passages WHERE attachmentId IN (:ids) ORDER BY attachmentId ASC, ord ASC")
    suspend fun forAttachments(ids: List<String>): List<PassageEntity>
    // F20: carga restrita com teto no SQL (publicações grandes têm 100k+ linhas).
    @Query("SELECT * FROM passages WHERE attachmentId IN (:ids) ORDER BY attachmentId ASC, ord ASC LIMIT :limit")
    suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity>
    // T3 (refs): fatia da unidade citada (artigo/lição) por ord.
    @Query("SELECT * FROM passages WHERE attachmentId = :attachmentId AND ord BETWEEN :from AND :to ORDER BY ord LIMIT :limit")
    suspend fun betweenOrd(attachmentId: String, from: Int, to: Int, limit: Int): List<PassageEntity>
    @Query("SELECT * FROM passages WHERE attachmentId = :attachmentId AND text LIKE '# %' AND ord > :ord ORDER BY ord LIMIT 1")
    suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity?
    // T4 (refs): passagens de uma seção (unidade citada) pelo rótulo.
    @Query("SELECT * FROM passages WHERE attachmentId = :attachmentId AND section LIKE '%' || :needle || '%' ORDER BY ord LIMIT :limit")
    suspend fun bySection(attachmentId: String, needle: String, limit: Int): List<PassageEntity>
}

@Database(
    entities = [FolderEntity::class, NoteEntity::class, AttachmentEntity::class, PassageEntity::class, TombstoneEntity::class, OutlineEntity::class, ChatEntity::class, S34OutlineEntity::class, S34SectionEntity::class, S34SubsectionEntity::class, S34ReferenceEntity::class, SpeechSectionEntity::class, SubPointEntity::class],
    version = 16,
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
    abstract fun s34Dao(): S34Dao
    abstract fun speechSectionDao(): SpeechSectionDao
    abstract fun subPointDao(): SubPointDao
}

@Entity(
    tableName = "speech_sections",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("noteId"),
        Index(value = ["noteId", "order"], unique = true),
    ],
)
data class SpeechSectionEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val order: Int,
    /** SectionRole.name (INTRO/BODY/CONCLUSION). */
    val role: String,
    val title: String,
    val minutes: Int,
    val contentHtml: String,
    /** JSON array de strings (codec de S34OutlineRepository). */
    val bibleRefsJson: String,
    /** JSON array de objetos {symbol[,page,paragraph]}. */
    val publicationRefsJson: String,
    val methodPrinciple: String?,
    /** F2.3: objetivo do tópico (do esboço importado ou aceito pelo usuário). */
    val objective: String? = null,
    /** F2.3: abordagem acordada do tópico (decisão consolidada do usuário). */
    val agreedApproach: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Sub-ponto de uma seção BODY (FK → speech_sections, CASCADE).
 *
 * Convenção: BODY usa sub-pontos; INTRO/CONCLUSION usam `contentHtml`.
 * `developedHtml` pode ser vazio (rascunho); `outlineText` é a âncora.
 */
@Entity(
    tableName = "sub_points",
    foreignKeys = [ForeignKey(
        entity = SpeechSectionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sectionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("sectionId"),
        Index(value = ["sectionId", "order"], unique = true),
    ],
)
data class SubPointEntity(
    @PrimaryKey val id: String,
    val sectionId: String,
    val order: Int,
    val outlineText: String,
    /** JSON array de strings (codec de S34OutlineRepository). */
    val bibleRefsJson: String,
    /** JSON array de objetos {symbol[,page,paragraph]}. */
    val publicationRefsJson: String,
    val instruction: String?,
    val developedHtml: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Mensagens do chat com o Copilot (local, por nota; não sincroniza). */
@Entity(tableName = "chat_messages")
data class ChatEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val fromMe: Boolean,
    /** text | ideas | refs | outline_refs */
    val kind: String,
    val payload: String,
    val createdAt: Long,
    /**
     * F2.3: escopo da conversa (id da seção/tópico). null = conversa global
     * da nota (mensagens antigas, sem migration de conteúdo).
     */
    val sectionId: String? = null,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun observe(noteId: String): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chat_messages WHERE noteId = :noteId ORDER BY createdAt ASC")
    suspend fun all(noteId: String): List<ChatEntity>
    /** F2.3: conversa de um tópico (ou a global, quando [sectionId] é null). */
    @Query("SELECT * FROM chat_messages WHERE noteId = :noteId AND sectionId IS :sectionId ORDER BY createdAt ASC")
    fun observeScoped(noteId: String, sectionId: String?): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chat_messages WHERE noteId = :noteId AND sectionId IS :sectionId ORDER BY createdAt ASC")
    suspend fun allScoped(noteId: String, sectionId: String?): List<ChatEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(m: ChatEntity)
    @Query("DELETE FROM chat_messages WHERE noteId = :noteId")
    suspend fun clear(noteId: String)
    /** F2.3: limpa só a conversa do escopo (tópico ou global). */
    @Query("DELETE FROM chat_messages WHERE noteId = :noteId AND sectionId IS :sectionId")
    suspend fun clearScoped(noteId: String, sectionId: String?)
    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SpeechSectionDao {
    @Query("SELECT * FROM speech_sections WHERE noteId = :noteId ORDER BY `order`")
    suspend fun forNote(noteId: String): List<SpeechSectionEntity>

    /** 3.2.5a: observa as seções da nota (Flow) — consumido pelo SectionsController. */
    @Query("SELECT * FROM speech_sections WHERE noteId = :noteId ORDER BY `order`")
    fun observeForNote(noteId: String): Flow<List<SpeechSectionEntity>>

    @Query("SELECT * FROM speech_sections WHERE id = :id")
    suspend fun get(id: String): SpeechSectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(section: SpeechSectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sections: List<SpeechSectionEntity>)

    @Delete
    suspend fun delete(section: SpeechSectionEntity)

    @Query("DELETE FROM speech_sections WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)
}

@Dao
interface SubPointDao {
    @Query("SELECT * FROM sub_points WHERE sectionId = :sectionId ORDER BY `order`")
    suspend fun forSection(sectionId: String): List<SubPointEntity>

    @Query("SELECT * FROM sub_points WHERE id = :id")
    suspend fun get(id: String): SubPointEntity?

    @Query("SELECT * FROM sub_points WHERE sectionId IN (:sectionIds) ORDER BY sectionId, `order`")
    suspend fun forSections(sectionIds: List<String>): List<SubPointEntity>

    /**
     * 3.2.5a: observa sub-pontos de N seções (Flow) — consumido pelo
     * SectionsController. NÃO chamar com lista vazia (guard no collector).
     */
    @Query("SELECT * FROM sub_points WHERE sectionId IN (:sectionIds) ORDER BY sectionId, `order`")
    fun observeForSections(sectionIds: List<String>): Flow<List<SubPointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subPoint: SubPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(subPoints: List<SubPointEntity>)

    @Delete
    suspend fun delete(subPoint: SubPointEntity)

    @Query("DELETE FROM sub_points WHERE sectionId = :sectionId")
    suspend fun deleteForSection(sectionId: String)
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

/**
 * Fase 19-B.3 — persistência do S34Document (parser B.2).
 * Tabelas normalizadas; `order` do domínio vira coluna `position`
 * (`order` é palavra reservada do SQLite). Derivado do attachment:
 * sem FK formal (convenção do projeto: deleção manual explícita).
 */
@Entity(
    tableName = "s34_outlines",
    indices = [Index("sourceAttachmentId")]
)
data class S34OutlineEntity(
    @PrimaryKey val id: String,
    val sourceAttachmentId: String,
    val symbol: String,
    val title: String,
    val objective: String?,
    /** Linhas órfãs pré-seção (JSON array de strings; texto não-estruturado). */
    val headerLinesJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long,
    val parserVersion: Int
)

@Entity(
    tableName = "s34_sections",
    indices = [Index("outlineId")]
)
data class S34SectionEntity(
    @PrimaryKey val id: String,
    val outlineId: String,
    @androidx.room.ColumnInfo(name = "position") val order: Int,
    val title: String,
    val content: String,
    val minutes: Int?,
    val sourceLine: Int
)

@Entity(
    tableName = "s34_subsections",
    indices = [Index("sectionId")]
)
data class S34SubsectionEntity(
    @PrimaryKey val id: String,
    val sectionId: String,
    @androidx.room.ColumnInfo(name = "position") val order: Int,
    val content: String,
    val sourceLine: Int
)

@Entity(
    tableName = "s34_references",
    indices = [Index("outlineId"), Index("sectionId")]
)
data class S34ReferenceEntity(
    @PrimaryKey val id: String,
    val outlineId: String,
    /** Null = fora de seção (contrato B.2: sem associação inventada). */
    val sectionId: String?,
    val subsectionId: String?,
    @androidx.room.ColumnInfo(name = "position") val order: Int,
    /** BIBLE | PUBLICATION */
    val type: String,
    val rawText: String,
    val normalizedReference: String,
    val sourceLine: Int,
    val book: String?,
    val bookNorm: String?,
    val chapter: Int?,
    val verse: Int?,
    /** MAGAZINE | BOOK (nome do enum; null quando não-publicação). */
    val pubKind: String?,
    val pubKey: String?,
    val pubLabel: String?,
    val editionKey: String?
)

@Dao
interface S34Dao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOutline(o: S34OutlineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSections(list: List<S34SectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSubsections(list: List<S34SubsectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReferences(list: List<S34ReferenceEntity>)

    @Query("SELECT * FROM s34_outlines WHERE id = :id LIMIT 1")
    suspend fun outlineById(id: String): S34OutlineEntity?

    @Query("SELECT * FROM s34_outlines WHERE sourceAttachmentId = :sourceId LIMIT 1")
    suspend fun outlineBySource(sourceId: String): S34OutlineEntity?

    @Query("SELECT * FROM s34_sections WHERE outlineId = :outlineId ORDER BY position ASC")
    suspend fun sectionsOf(outlineId: String): List<S34SectionEntity>

    @Query("SELECT * FROM s34_subsections WHERE sectionId IN (:sectionIds) ORDER BY sectionId ASC, position ASC")
    suspend fun subsectionsOf(sectionIds: List<String>): List<S34SubsectionEntity>

    @Query("SELECT * FROM s34_references WHERE outlineId = :outlineId ORDER BY position ASC")
    suspend fun referencesOf(outlineId: String): List<S34ReferenceEntity>

    @Query("DELETE FROM s34_references WHERE outlineId = :outlineId")
    suspend fun deleteRefsOf(outlineId: String)

    @Query("DELETE FROM s34_subsections WHERE sectionId IN (:sectionIds)")
    suspend fun deleteSubsOf(sectionIds: List<String>)

    @Query("DELETE FROM s34_sections WHERE outlineId = :outlineId")
    suspend fun deleteSectionsOf(outlineId: String)

    @Query("DELETE FROM s34_outlines WHERE id = :id")
    suspend fun deleteOutline(id: String)

    @Query("DELETE FROM s34_outlines WHERE sourceAttachmentId = :sourceId")
    suspend fun deleteOutlineBySource(sourceId: String)
}
