package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.NoteDao
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.TombstoneDao
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.util.plainFromMarkdown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------- Fakes (padrão do projeto: JVM puro, sem SDK/Room) ----------

/** Fake Map-based do contrato remoto; `set` faz MERGE (como SetOptions.merge). */
private class FakeSyncRemote : SyncRemote {
    private val collections = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
    private fun col(name: String) = collections.getOrPut(name) { mutableMapOf() }

    override suspend fun getAll(collection: String): Map<String, Map<String, Any?>> =
        col(collection).mapValues { it.value.toMap() }

    override suspend fun get(collection: String, id: String): Map<String, Any?>? =
        col(collection)[id]?.toMap()

    override suspend fun set(collection: String, id: String, data: Map<String, Any?>) {
        col(collection).getOrPut(id) { mutableMapOf() }.putAll(data)
    }

    override suspend fun delete(collection: String, id: String) {
        col(collection).remove(id)
    }

    fun doc(collection: String, id: String): Map<String, Any?>? = col(collection)[id]
    fun ids(collection: String): Set<String> = col(collection).keys.toSet()
}

private class FakeNoteDao(rows: List<NoteEntity> = emptyList()) : NoteDao {
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

private class FakeTombstoneDao(rows: List<TombstoneEntity> = emptyList()) : TombstoneDao {
    var rows: MutableList<TombstoneEntity> = rows.toMutableList()
    override suspend fun all(): List<TombstoneEntity> = rows.toList()
    override suspend fun put(t: TombstoneEntity) {
        rows = rows.filter { it.id != t.id }.toMutableList().also { it.add(t) }
    }
    override suspend fun remove(id: String) {
        rows = rows.filter { it.id != id }.toMutableList()
    }
}

// ---------- Fixtures ----------

private fun note(
    id: String = "n1",
    title: String = "Teste round-trip",
    md: String = "Corpo da nota.",
    trashed: Boolean = false,
    updatedAt: Long = 1000L,
    createdAt: Long = 500L,
) = NoteEntity(
    id = id, title = title, mdText = md, plainText = plainFromMarkdown(md),
    folderId = null, colorArgb = 0L, pinned = false, trashed = trashed,
    createdAt = createdAt, updatedAt = updatedAt,
    richHtml = "<p>$md</p>",
)

/**
 * F7a — primeiros testes do SyncWorker em JVM, via seam [SyncRemote]
 * (fake Map-based) + fakes dos DAOs (mesmo padrão do OutlineImportServiceTest).
 */
class SyncNotesRemoteTest {

    private val remote = FakeSyncRemote()

    // 1. Remote vazio + 1 nota local → push.
    @Test
    fun remoteVazio_umLocal_empurra() = runBlocking {
        val dao = FakeNoteDao(listOf(note()))
        syncNotes(remote, dao, FakeTombstoneDao())

        assertEquals(setOf("n1"), remote.ids("notes"))
        val doc = remote.doc("notes", "n1")!!
        assertEquals("Teste round-trip", doc["title"])
        assertEquals(1000L, doc["updatedAt"])
        assertEquals(1, dao.rows.size) // local intacto
    }

    // 2. Remote com 1 nota, local vazio → pull.
    @Test
    fun remoteComNota_localVazio_puxa() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Da nuvem", "mdText" to "Texto.", "richHtml" to "<p>Texto.</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 20L,
        ))
        val dao = FakeNoteDao()
        syncNotes(remote, dao, FakeTombstoneDao())

        val n = dao.rows.single()
        assertEquals("n1", n.id)
        assertEquals("Da nuvem", n.title)
        assertEquals(20L, n.updatedAt)
        assertEquals(plainFromMarkdown("Texto."), n.plainText)
    }

    // 3a. LWW: local mais novo → push (remoto atualizado, local intacto).
    @Test
    fun lww_localMaisNovo_empurra() = runBlocking {
        remote.set("notes", "n1", mapOf("title" to "Antiga", "updatedAt" to 100L))
        val dao = FakeNoteDao(listOf(note(updatedAt = 200L)))
        syncNotes(remote, dao, FakeTombstoneDao())

        assertEquals("Teste round-trip", remote.doc("notes", "n1")!!["title"])
        assertEquals(200L, remote.doc("notes", "n1")!!["updatedAt"])
        assertEquals("Teste round-trip", dao.rows.single().title)
    }

    // 3b. LWW: remoto mais novo → pull (local sobrescrito).
    @Test
    fun lww_remotoMaisNovo_puxa() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Nova da nuvem", "mdText" to "x", "updatedAt" to 300L,
            "createdAt" to 10L, "trashed" to false,
        ))
        val dao = FakeNoteDao(listOf(note(title = "Local antiga", updatedAt = 200L)))
        syncNotes(remote, dao, FakeTombstoneDao())

        assertEquals("Nova da nuvem", dao.rows.single().title)
        assertEquals(300L, dao.rows.single().updatedAt)
    }

    // 4a. Proteção: remoto trashed mais novo NÃO lixa nota local viva.
    @Test
    fun remotoTrashedMaisNovo_notaViva_naoLixa() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Lixada na nuvem", "updatedAt" to 300L, "trashed" to true,
        ))
        val dao = FakeNoteDao(listOf(note(updatedAt = 200L)))
        syncNotes(remote, dao, FakeTombstoneDao())

        val local = dao.rows.single()
        assertFalse(local.trashed)
        assertEquals("Teste round-trip", local.title)
    }

    // 4b. Nota local JÁ trashed + remoto trashed mais novo → aplica (idempotente).
    @Test
    fun remotoTrashedMaisNovo_localJaTrashed_aplica() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Lixada na nuvem", "updatedAt" to 300L, "trashed" to true,
        ))
        val dao = FakeNoteDao(listOf(note(trashed = true, updatedAt = 200L)))
        syncNotes(remote, dao, FakeTombstoneDao())

        val local = dao.rows.single()
        assertTrue(local.trashed)
        assertEquals("Lixada na nuvem", local.title)
    }

    // 5a. Tombstone local de nota → doc removido do remoto.
    @Test
    fun tombstoneLocal_removeDocRemoto() = runBlocking {
        remote.set("notes", "n-apagada", mapOf("title" to "X", "updatedAt" to 1L))
        val dao = FakeNoteDao()
        val tombs = FakeTombstoneDao(listOf(TombstoneEntity("n-apagada", "note", 1L)))
        syncNotes(remote, dao, tombs)

        assertNull(remote.doc("notes", "n-apagada"))
        // comportamento atual: doc EXISTIA no cloud lido antes → tombstone permanece
        assertEquals(1, tombs.rows.size)
    }

    // 5b. Tombstone de nota que já não existia no remoto → limpo localmente.
    @Test
    fun tombstoneLocal_fantasma_limpaTombstone() = runBlocking {
        val dao = FakeNoteDao()
        val tombs = FakeTombstoneDao(listOf(TombstoneEntity("n-fantasma", "note", 1L)))
        syncNotes(remote, dao, tombs)

        assertTrue(tombs.rows.isEmpty())
    }

    // 6. Round-trip: push → limpar local → pull → estado igual (o teste do A34 em JVM).
    @Test
    fun roundTrip_notaVoltaIgual() = runBlocking {
        val original = note(id = "rt", title = "Teste round-trip", md = "Parágrafos.", updatedAt = 777L)
        syncNotes(remote, FakeNoteDao(listOf(original)), FakeTombstoneDao())

        val localDepois = FakeNoteDao() // "reinstalação": local zerado
        syncNotes(remote, localDepois, FakeTombstoneDao())

        val restored = localDepois.rows.single()
        assertEquals(original, restored)
        assertNotNull(remote.doc("notes", "rt"))
    }
}
