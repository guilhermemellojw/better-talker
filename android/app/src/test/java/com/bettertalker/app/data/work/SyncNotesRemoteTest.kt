package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.util.plainFromMarkdown
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------- Fakes ----------
// FakeSyncRemote/FakeNoteDao/FakeTombstoneDao (extraídos na F7b) vivem em
// TestFakes.kt, compartilhados com o SyncSectionsRemoteTest.

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
        // LWW com horário de edição (o guard de skew valida o relógio).
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
        // O updatedAt volta do push como Long (horário de edição validado
        // pelo guard); compara o conteúdo com o campo normalizado.
        assertEquals(original.copy(updatedAt = 0L), restored.copy(updatedAt = 0L))
        assertNotNull(remote.doc("notes", "rt"))
    }

    // 7. Matriz de trash (integração, fixture): remoto VIVO mais novo →
    // restaura nota trashed local (remoteCanTrashLocal=true).
    @Test
    fun remotoVivoMaisNovo_localTrashed_restaura() = runBlocking {
        val dao = FakeNoteDao(listOf(note(id = "n1", trashed = true, updatedAt = 1000L)))
        remote.set("notes", "n1", mapOf(
            "title" to "Restaurada", "mdText" to "y", "richHtml" to "<p>y</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 2000L,
        ))
        syncNotes(remote, dao, FakeTombstoneDao())

        assertFalse(dao.get("n1")!!.trashed)
        assertEquals("Restaurada", dao.get("n1")!!.title)
    }

    // 8. Leitura tolerante do updatedAt (Timestamp legado e Long novo).
    @Test
    fun cloudUpdatedAt_aceitaTimestampELongLegado() {
        assertEquals(123L, cloudUpdatedAt(mapOf("updatedAt" to 123L)))
        val ts = com.google.firebase.Timestamp(java.util.Date(456L))
        assertEquals(456L, cloudUpdatedAt(mapOf("updatedAt" to ts)))
        assertEquals(0L, cloudUpdatedAt(emptyMap()))
    }

    // 9. Guard de skew: 2h falha; 4min passa.
    @Test
    fun clockSkew_duasHoras_falha() {
        val now = 1_700_000_000_000L
        assertTrue(clockSkewExceeded(serverMs = now, localMs = now - 2 * 60 * 60 * 1000L))
        assertTrue(clockSkewExceeded(serverMs = now, localMs = now + 2 * 60 * 60 * 1000L))
    }

    @Test
    fun clockSkew_quatroMinutos_passa() {
        val now = 1_700_000_000_000L
        assertFalse(clockSkewExceeded(serverMs = now, localMs = now - 4 * 60 * 1000L))
        assertFalse(clockSkewExceeded(serverMs = now, localMs = now + 4 * 60 * 1000L))
    }

    // 10. Delete forever em A -> B não ressuscita.
    @Test
    fun tombstoneRemoto_apagaNotaLocalENaoRessuscita() = runBlocking {
        remote.set("notes", "n1", mapOf("title" to "X", "updatedAt" to 10L))
        remote.set("sections", "s1", mapOf("noteId" to "n1", "updatedAt" to 10L))
        remote.set("subPoints", "p1", mapOf("sectionId" to "s1", "updatedAt" to 10L))
        remote.set(
            "tombstones", "n1",
            mapOf("type" to "note", "deletedAt" to 20L),
        )
        val remoteTombs = remote.getAll("tombstones")
        val dao = FakeNoteDao(listOf(note(id = "n1", updatedAt = 30L)))

        syncNotes(remote, dao, FakeTombstoneDao(), remoteTombs)

        assertTrue(dao.rows.isEmpty())
        assertNull(remote.doc("notes", "n1"))
        assertNull(remote.doc("sections", "s1"))
        assertNull(remote.doc("subPoints", "p1"))
        assertNotNull(remote.doc("tombstones", "n1"))
    }
}
