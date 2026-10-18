package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.NoteDao
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TombstoneDao
import com.bettertalker.app.data.db.TombstoneEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fakes compartilhados dos testes de sync em JVM (padrão do projeto: sem
 * SDK/Room).
 *
 * Extraídos do `SyncNotesRemoteTest` (F7a) na F7b para o
 * `SyncSectionsRemoteTest` reusar — mesmo contrato, sem mudanças nos casos
 * do F7a. Duas extensões feitas na extração, ambas para espelhar melhor o
 * Firestore real:
 * - [FakeSyncRemote.set] NORMALIZA inteiros Int -> Long (o Firestore devolve
 *   todo inteiro como Long; os parsers `cloudSection`/`cloudSubPoint` leem
 *   `as? Long`, então sem isso `order`/`minutes` voltariam 0 no round-trip).
 * - [FakeSyncRemote.deleted] registra a ordem real das exclusões (prova a
 *   cascata sub-pontos -> seções -> nota do `deleteNoteDeep`).
 */

/** Fake Map-based do contrato [SyncRemote]; `set` faz MERGE (como SetOptions.merge). */
internal class FakeSyncRemote : SyncRemote {
    private val collections = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()

    /** Ordem real das exclusões (collection -> id), para asserções de cascata. */
    val deleted: MutableList<Pair<String, String>> = mutableListOf()

    private fun col(name: String) = collections.getOrPut(name) { mutableMapOf() }

    override suspend fun getAll(collection: String): Map<String, Map<String, Any?>> =
        col(collection).mapValues { it.value.toMap() }

    override suspend fun get(collection: String, id: String): Map<String, Any?>? =
        col(collection)[id]?.toMap()

    override suspend fun set(collection: String, id: String, data: Map<String, Any?>) {
        val doc = col(collection).getOrPut(id) { mutableMapOf() }
        data.forEach { (k, v) -> doc[k] = normalize(v) }
    }

    override suspend fun delete(collection: String, id: String) {
        deleted += collection to id
        col(collection).remove(id)
    }

    fun doc(collection: String, id: String): Map<String, Any?>? = col(collection)[id]
    fun ids(collection: String): Set<String> = col(collection).keys.toSet()

    /** Firestore devolve inteiros como Long; o fake imita (recursivo em mapas/listas). */
    private fun normalize(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        // Gap C: simula a resolução do serverTimestamp (o servidor troca o
        // sentinel por um Timestamp real; aqui usamos "agora").
        is com.google.firebase.firestore.FieldValue ->
            com.google.firebase.Timestamp(java.util.Date())
        is Map<*, *> -> value.entries.associate { (k, v) -> k to normalize(v) }
        is List<*> -> value.map { normalize(it) }
        else -> value
    }
}

internal class FakeNoteDao(rows: List<NoteEntity> = emptyList()) : NoteDao {
    var rows: MutableList<NoteEntity> = rows.toMutableList()

    override fun observe(folderId: String?, q: String): Flow<List<NoteEntity>> =
        flowOf(this.rows.filter { !it.trashed })

    override suspend fun get(id: String): NoteEntity? = rows.firstOrNull { it.id == id }

    override fun observeById(id: String): Flow<NoteEntity?> =
        flowOf(rows.firstOrNull { it.id == id })

    override suspend fun upsert(note: NoteEntity) {
        rows = rows.filter { it.id != note.id }.toMutableList().also { it.add(note) }
    }

    override suspend fun trash(id: String, now: Long) {
        rows = rows.map { if (it.id == id) it.copy(trashed = true, updatedAt = now) else it }
            .toMutableList()
    }

    override suspend fun togglePin(id: String, now: Long) {
        rows = rows.map { if (it.id == id) it.copy(pinned = !it.pinned, updatedAt = now) else it }
            .toMutableList()
    }

    override suspend fun restore(id: String, now: Long) {
        rows = rows.map { if (it.id == id) it.copy(trashed = false, updatedAt = now) else it }
            .toMutableList()
    }

    override suspend fun move(id: String, folderId: String?, now: Long) {
        rows = rows.map { if (it.id == id) it.copy(folderId = folderId, updatedAt = now) else it }
            .toMutableList()
    }

    override fun observeTrashed(): Flow<List<NoteEntity>> = flowOf(rows.filter { it.trashed })

    override suspend fun allIncludingTrashed(): List<NoteEntity> = rows.toList()

    override suspend fun delete(id: String) {
        rows = rows.filter { it.id != id }.toMutableList()
    }

    override suspend fun clearFolder(folderId: String) {
        rows = rows.map { if (it.folderId == folderId) it.copy(folderId = null) else it }
            .toMutableList()
    }

    override suspend fun count(): Int = rows.count { !it.trashed }
}

internal class FakeTombstoneDao(rows: List<TombstoneEntity> = emptyList()) : TombstoneDao {
    var rows: MutableList<TombstoneEntity> = rows.toMutableList()
    override suspend fun all(): List<TombstoneEntity> = rows.toList()
    override suspend fun put(t: TombstoneEntity) {
        rows = rows.filter { it.id != t.id }.toMutableList().also { it.add(t) }
    }
    override suspend fun remove(id: String) {
        rows = rows.filter { it.id != id }.toMutableList()
    }
}

internal class FakeSectionDao(rows: List<SpeechSectionEntity> = emptyList()) : SpeechSectionDao {
    var rows: MutableList<SpeechSectionEntity> = rows.toMutableList()
    override suspend fun forNote(noteId: String): List<SpeechSectionEntity> =
        rows.filter { it.noteId == noteId }.sortedBy { it.order }
    override fun observeForNote(noteId: String): Flow<List<SpeechSectionEntity>> =
        flowOf(rows.filter { it.noteId == noteId })
    override suspend fun get(id: String): SpeechSectionEntity? = rows.firstOrNull { it.id == id }
    override suspend fun upsert(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id }.toMutableList().also { it.add(section) }
    }
    override suspend fun upsertAll(sections: List<SpeechSectionEntity>) { sections.forEach { upsert(it) } }
    override suspend fun delete(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id }.toMutableList()
    }
    override suspend fun deleteForNote(noteId: String) {
        rows = rows.filter { it.noteId != noteId }.toMutableList()
    }
}

internal class FakeSubPointDao(rows: List<SubPointEntity> = emptyList()) : SubPointDao {
    var rows: MutableList<SubPointEntity> = rows.toMutableList()
    override suspend fun forSection(sectionId: String): List<SubPointEntity> =
        rows.filter { it.sectionId == sectionId }.sortedBy { it.order }
    override suspend fun get(id: String): SubPointEntity? = rows.firstOrNull { it.id == id }
    override suspend fun forSections(sectionIds: List<String>): List<SubPointEntity> =
        rows.filter { it.sectionId in sectionIds }
    override fun observeForSections(sectionIds: List<String>): Flow<List<SubPointEntity>> =
        flowOf(rows.filter { it.sectionId in sectionIds })
    override suspend fun upsert(subPoint: SubPointEntity) {
        rows = rows.filter { it.id != subPoint.id }.toMutableList().also { it.add(subPoint) }
    }
    override suspend fun upsertAll(subPoints: List<SubPointEntity>) { subPoints.forEach { upsert(it) } }
    override suspend fun delete(subPoint: SubPointEntity) {
        rows = rows.filter { it.id != subPoint.id }.toMutableList()
    }
    override suspend fun deleteForSection(sectionId: String) {
        rows = rows.filter { it.sectionId != sectionId }.toMutableList()
    }
}
