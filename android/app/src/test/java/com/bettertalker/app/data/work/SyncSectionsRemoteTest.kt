package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.db.encodePublicationRefList
import com.bettertalker.app.data.repo.encodeStringList
import com.bettertalker.app.data.util.plainFromMarkdown
import com.bettertalker.app.domain.planning.PublicationRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------- Fixtures ----------
// Fakes em TestFakes.kt (FakeSyncRemote/FakeNoteDao/FakeTombstoneDao/
// FakeSectionDao/FakeSubPointDao), compartilhados com o SyncNotesRemoteTest.

/** Nota viva (default), com todos os campos preenchidos para o round-trip. */
private fun note(
    id: String = "n1",
    updatedAt: Long = 100L,
) = NoteEntity(
    id = id, title = "Nota $id", mdText = "Corpo.", plainText = plainFromMarkdown("Corpo."),
    folderId = null, colorArgb = 0L, pinned = false, trashed = false,
    createdAt = 50L, updatedAt = updatedAt, richHtml = "<p>Corpo.</p>",
)

/** Seção com TODOS os campos não-nulos: prova que o mapeamento cobre tudo. */
private fun section(
    id: String,
    noteId: String = "n1",
    order: Int = 0,
    title: String = "Seção $id",
    minutes: Int = 5,
    updatedAt: Long = 100L,
) = SpeechSectionEntity(
    id = id, noteId = noteId, order = order, role = "BODY", title = title,
    minutes = minutes, contentHtml = "<p>Texto</p>",
    bibleRefsJson = encodeStringList(listOf("Jo 3:16")),
    publicationRefsJson = encodePublicationRefList(listOf(PublicationRef("w13 15/5", page = 12))),
    methodPrinciple = "be 21", objective = "Objetivo $id", agreedApproach = "Abordagem $id",
    approvedAt = 123L, createdAt = 50L, updatedAt = updatedAt,
)

/** Sub-ponto com TODOS os campos não-nulos: prova que o mapeamento cobre tudo. */
private fun subPoint(
    id: String,
    sectionId: String = "s1",
    order: Int = 0,
    updatedAt: Long = 100L,
) = SubPointEntity(
    id = id, sectionId = sectionId, order = order, outlineText = "Ponto $id",
    bibleRefsJson = encodeStringList(listOf("Jo 3:16")),
    publicationRefsJson = encodePublicationRefList(listOf(PublicationRef("w13 15/5"))),
    instruction = "[Leia Jo 3:16.]", developedHtml = "<p>Desenvolvido $id</p>",
    createdAt = 50L, updatedAt = updatedAt,
)

/**
 * F7b — sync de seções/sub-pontos via seam [SyncRemote] (fake Map-based em
 * TestFakes.kt) + fakes dos DAOs: push, pull, LWW, órfãos, cascata de nota
 * (`deleteNoteDeep`, 3 níveis) e o round-trip E2E que falhava no A34.
 */
class SyncSectionsRemoteTest {

    private val remote = FakeSyncRemote()

    // 1. Local 3 seções (nota viva), remoto vazio → após syncSections, 3 docs.
    @Test
    fun local3Secoes_remotoVazio_empurra() = runBlocking {
        val noteDao = FakeNoteDao(listOf(note()))
        val secDao = FakeSectionDao(listOf(
            section("s1", order = 0), section("s2", order = 1), section("s3", order = 2),
        ))

        syncSections(remote, secDao, noteDao)

        assertEquals(setOf("s1", "s2", "s3"), remote.ids("sections"))
        // Doc da s1 cobre TODOS os 14 campos do corpo + id como chave.
        val doc = remote.doc("sections", "s1")!!
        assertEquals("n1", doc["noteId"])
        assertEquals(0L, doc["order"])
        assertEquals("BODY", doc["role"])
        assertEquals("Seção s1", doc["title"])
        assertEquals(5L, doc["minutes"])
        assertEquals("<p>Texto</p>", doc["contentHtml"])
        assertEquals(listOf("Jo 3:16"), doc["bibleRefs"])
        assertEquals(listOf(mapOf("symbol" to "w13 15/5", "page" to 12L)), doc["publicationRefs"])
        assertEquals("be 21", doc["methodPrinciple"])
        assertEquals("Objetivo s1", doc["objective"])
        assertEquals("Abordagem s1", doc["agreedApproach"])
        assertEquals(123L, doc["approvedAt"])
        assertEquals(50L, doc["createdAt"])
        assertTrue(doc["updatedAt"] is com.google.firebase.Timestamp)
        assertEquals(3, secDao.rows.size) // local intacto
    }

    // 2. Remoto 2 seções, local vazio (nota viva) → após syncSections, local tem 2.
    @Test
    fun remoto2Secoes_localVazio_puxa() = runBlocking {
        remote.set("sections", "s1", mapOf(
            "noteId" to "n1", "order" to 0L, "role" to "INTRO", "title" to "Da nuvem",
            "minutes" to 4L, "contentHtml" to "<p>x</p>",
            "bibleRefs" to listOf("Rm 5:12"), "createdAt" to 10L, "updatedAt" to 20L,
        ))
        remote.set("sections", "s2", mapOf("noteId" to "n1", "order" to 1L, "updatedAt" to 30L))
        val noteDao = FakeNoteDao(listOf(note()))
        val secDao = FakeSectionDao()

        syncSections(remote, secDao, noteDao)

        assertEquals(2, secDao.rows.size)
        val s1 = secDao.rows.first { it.id == "s1" }
        assertEquals("n1", s1.noteId)
        assertEquals(0, s1.order)
        assertEquals("INTRO", s1.role)
        assertEquals("Da nuvem", s1.title)
        assertEquals(4, s1.minutes)
        assertEquals("<p>x</p>", s1.contentHtml)
        assertEquals(encodeStringList(listOf("Rm 5:12")), s1.bibleRefsJson)
        assertEquals(10L, s1.createdAt)
        assertEquals(20L, s1.updatedAt)
        val s2 = secDao.rows.first { it.id == "s2" }
        assertEquals(1, s2.order)
        assertEquals(30L, s2.updatedAt)
    }

    // 3. LWW: local updatedAt > remoto → push; local menor → pull.
    @Test
    fun lww_localMaisNovoEmpurra_remotoMaisNovoPuxa() = runBlocking {
        remote.set("sections", "s1", mapOf("noteId" to "n1", "title" to "Antiga", "updatedAt" to 100L))
        remote.set("sections", "s2", mapOf("noteId" to "n1", "title" to "Nova", "updatedAt" to 500L))
        val noteDao = FakeNoteDao(listOf(note()))
        val secDao = FakeSectionDao(listOf(
            section("s1", title = "Local nova", updatedAt = 300L),
            section("s2", title = "Local antiga", updatedAt = 200L),
        ))

        syncSections(remote, secDao, noteDao)

        // push: remoto s1 passa a ser o local mais novo.
        assertEquals("Local nova", remote.doc("sections", "s1")!!["title"])
        assertTrue(remote.doc("sections", "s1")!!["updatedAt"] is com.google.firebase.Timestamp)
        // pull: local s2 passa a ser o remoto mais novo.
        assertEquals("Nova", secDao.rows.first { it.id == "s2" }.title)
        assertEquals(500L, secDao.rows.first { it.id == "s2" }.updatedAt)
        // e o local vencedor fica intacto.
        assertEquals("Local nova", secDao.rows.first { it.id == "s1" }.title)
    }

    // 4a. deleteNoteDeep: cascata de 3 níveis na ordem sub-pontos → seções → nota.
    @Test
    fun deleteNoteDeep_apagaSubsSecoesNota_naOrdem() = runBlocking {
        remote.set("notes", "n1", mapOf("title" to "X"))
        remote.set("sections", "s1", mapOf("noteId" to "n1"))
        remote.set("sections", "s2", mapOf("noteId" to "n1"))
        remote.set("sections", "s3", mapOf("noteId" to "n2")) // de OUTRA nota
        remote.set("subPoints", "p1", mapOf("sectionId" to "s1"))
        remote.set("subPoints", "p2", mapOf("sectionId" to "s2"))
        remote.set("subPoints", "p3", mapOf("sectionId" to "s3")) // da outra nota

        deleteNoteDeep(remote, "n1")

        assertNull(remote.doc("notes", "n1"))
        assertNull(remote.doc("sections", "s1"))
        assertNull(remote.doc("sections", "s2"))
        assertNull(remote.doc("subPoints", "p1"))
        assertNull(remote.doc("subPoints", "p2"))
        // Documentos da outra nota ficam intactos.
        assertNotNull(remote.doc("sections", "s3"))
        assertNotNull(remote.doc("subPoints", "p3"))
        // O fake registra a ordem real: subs → seções → nota.
        assertEquals(
            listOf(
                "subPoints" to "p1", "subPoints" to "p2",
                "sections" to "s1", "sections" to "s2",
                "notes" to "n1",
            ),
            remote.deleted,
        )
    }

    // 4b. Tombstone de nota local via syncNotes → deleteNoteDeep limpa a cascata.
    @Test
    fun tombstoneDeNota_limpaCascataRemota() = runBlocking {
        remote.set("notes", "n1", mapOf("title" to "X", "updatedAt" to 1L))
        remote.set("sections", "s1", mapOf("noteId" to "n1", "updatedAt" to 1L))
        remote.set("subPoints", "p1", mapOf("sectionId" to "s1", "updatedAt" to 1L))
        remote.set("sections", "s2", mapOf("noteId" to "n2", "updatedAt" to 1L))
        remote.set("subPoints", "p2", mapOf("sectionId" to "s2", "updatedAt" to 1L))
        val tombs = FakeTombstoneDao(listOf(TombstoneEntity("n1", "note", 1L)))

        syncNotes(remote, FakeNoteDao(), tombs)

        assertNull(remote.doc("notes", "n1"))
        assertNull(remote.doc("sections", "s1"))
        assertNull(remote.doc("subPoints", "p1"))
        // A outra nota fica intacta.
        assertNotNull(remote.doc("sections", "s2"))
        assertNotNull(remote.doc("subPoints", "p2"))
        // Paridade com o caso 5a do F7a: doc existia no cloud → tombstone permanece.
        assertEquals(1, tombs.rows.size)
    }

    // 5. Sub-ponto órfão (seção dele não existe local) NÃO é ressuscitado.
    @Test
    fun subPontoOrfao_naoRessuscita() = runBlocking {
        remote.set("subPoints", "p1", mapOf(
            "sectionId" to "s1", "outlineText" to "Válido", "updatedAt" to 10L,
        ))
        remote.set("subPoints", "p-orfao", mapOf(
            "sectionId" to "s-inexistente", "outlineText" to "Órfão", "updatedAt" to 999L,
        ))
        val noteDao = FakeNoteDao(listOf(note()))
        val secDao = FakeSectionDao(listOf(section("s1")))
        val spDao = FakeSubPointDao()

        syncSubPoints(remote, spDao, secDao, noteDao)

        // Só o sub-ponto de seção viva é puxado; o órfão segue inexistente local.
        assertEquals(setOf("p1"), spDao.rows.map { it.id }.toSet())
        assertNull(spDao.rows.firstOrNull { it.id == "p-orfao" })
        // E o doc remoto órfão não é apagado nem alterado por engano.
        assertNotNull(remote.doc("subPoints", "p-orfao"))
        assertEquals("Órfão", remote.doc("subPoints", "p-orfao")!!["outlineText"])
    }

    // 6. Round-trip: 1 nota + 2 seções + 3 sub-pontos → push tudo → limpar
    //    local → pull → estado idêntico ao original (A34, agora em JVM).
    @Test
    fun roundTrip_nota2Secoes3SubPontos_estadoIdentico() = runBlocking {
        val originalNote = note("n1", updatedAt = 777L)
        val originalSections = listOf(section("s1", order = 0), section("s2", order = 1))
        val originalSubs = listOf(
            subPoint("p1", sectionId = "s1", order = 0),
            subPoint("p2", sectionId = "s1", order = 1),
            subPoint("p3", sectionId = "s2", order = 0),
        )

        // push: aparelho A (nota + seções + sub-pontos).
        val ndA = FakeNoteDao(listOf(originalNote))
        val sdA = FakeSectionDao(originalSections)
        val spA = FakeSubPointDao(originalSubs)
        syncNotes(remote, ndA, FakeTombstoneDao())
        syncSections(remote, sdA, ndA)
        syncSubPoints(remote, spA, sdA, ndA)
        assertEquals(setOf("s1", "s2"), remote.ids("sections"))
        assertEquals(setOf("p1", "p2", "p3"), remote.ids("subPoints"))

        // "Reinstalação": local zerado, mesmo remoto.
        val ndB = FakeNoteDao()
        val sdB = FakeSectionDao()
        val spB = FakeSubPointDao()
        syncNotes(remote, ndB, FakeTombstoneDao())
        syncSections(remote, sdB, ndB)
        syncSubPoints(remote, spB, sdB, ndB)

        // Estado pós-pull idêntico ao original (todos os campos; o updatedAt
        // volta como Timestamp do servidor — normalizado p/ comparação).
        assertEquals(
            originalNote.copy(updatedAt = 0L),
            ndB.rows.single().copy(updatedAt = 0L),
        )
        assertEquals(
            originalSections.sortedBy { it.id }.map { it.copy(updatedAt = 0L) },
            sdB.rows.sortedBy { it.id }.map { it.copy(updatedAt = 0L) },
        )
        assertEquals(
            originalSubs.sortedBy { it.id }.map { it.copy(updatedAt = 0L) },
            spB.rows.sortedBy { it.id }.map { it.copy(updatedAt = 0L) },
        )
    }

    // 7. Cascata bidirecional: local sobe tudo → nota apagada localmente com
    //    tombstone → próximo sync → remoto fica com 0 seções daquela nota.
    @Test
    fun cascataBidirecional_notaApagadaLocal_someDoRemoto() = runBlocking {
        // Estado inicial do aparelho A: n1 (2 seções/3 sub-pontos) e n2 (1/1).
        val ndA = FakeNoteDao(listOf(note("n1", updatedAt = 500L), note("n2", updatedAt = 500L)))
        val sdA = FakeSectionDao(listOf(
            section("s1"), section("s2", order = 1), section("s9", noteId = "n2"),
        ))
        val spA = FakeSubPointDao(listOf(
            subPoint("p1", sectionId = "s1"), subPoint("p2", sectionId = "s1", order = 1),
            subPoint("p3", sectionId = "s2"), subPoint("p9", sectionId = "s9"),
        ))
        syncNotes(remote, ndA, FakeTombstoneDao())
        syncSections(remote, sdA, ndA)
        syncSubPoints(remote, spA, sdA, ndA)
        assertEquals(setOf("s1", "s2", "s9"), remote.ids("sections"))
        assertEquals(setOf("p1", "p2", "p3", "p9"), remote.ids("subPoints"))

        // Usuário apaga n1: Room faz CASCADE local (seções/sub-pontos) e grava tombstone.
        ndA.delete("n1")
        sdA.deleteForNote("n1")
        spA.deleteForSection("s1")
        spA.deleteForSection("s2")
        val tombs = FakeTombstoneDao(listOf(TombstoneEntity("n1", "note", 600L)))

        // Próximo ciclo do doWork: notes -> sections -> subPoints.
        syncNotes(remote, ndA, tombs)
        syncSections(remote, sdA, ndA)
        syncSubPoints(remote, spA, sdA, ndA)

        // Remoto: 0 seções e 0 sub-pontos da nota n1; n2 intacta.
        assertNull(remote.doc("notes", "n1"))
        assertEquals(setOf("s9"), remote.ids("sections"))
        assertEquals(setOf("p9"), remote.ids("subPoints"))
        assertTrue(remote.getAll("sections").values.none { it["noteId"] == "n1" })
        assertTrue(remote.getAll("subPoints").values.none { it["sectionId"] == "s1" || it["sectionId"] == "s2" })
        assertEquals("Nota n2", remote.doc("notes", "n2")!!["title"])
        // Local também fica só com n2 (espelho do estado do aparelho A).
        assertEquals(setOf("n2"), ndA.rows.map { it.id }.toSet())
        assertEquals(setOf("s9"), sdA.rows.map { it.id }.toSet())
        assertEquals(setOf("p9"), spA.rows.map { it.id }.toSet())
    }

    // Extra: doc de sub-ponto cobre TODOS os 9 campos do corpo + id como chave.
    @Test
    fun subPonto_remotoVazio_empurraTodosOsCampos() = runBlocking {
        val noteDao = FakeNoteDao(listOf(note()))
        val secDao = FakeSectionDao(listOf(section("s1")))
        val spDao = FakeSubPointDao(listOf(subPoint("p1", sectionId = "s1", order = 1)))

        syncSubPoints(remote, spDao, secDao, noteDao)

        val doc = remote.doc("subPoints", "p1")!!
        assertEquals("s1", doc["sectionId"])
        assertEquals(1L, doc["order"])
        assertEquals("Ponto p1", doc["outlineText"])
        assertEquals("<p>Desenvolvido p1</p>", doc["developedHtml"])
        assertEquals("[Leia Jo 3:16.]", doc["instruction"])
        assertEquals(listOf("Jo 3:16"), doc["bibleRefs"])
        assertEquals(listOf(mapOf("symbol" to "w13 15/5")), doc["publicationRefs"])
        assertEquals(50L, doc["createdAt"])
        assertTrue(doc["updatedAt"] is com.google.firebase.Timestamp)
    }
}
