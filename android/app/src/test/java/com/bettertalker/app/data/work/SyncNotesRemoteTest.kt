package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.util.plainFromMarkdown
import com.bettertalker.app.domain.speech.DiscourseType
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
    lastSyncedAt: Long = 0L,
    discourseType: String = DiscourseType.S34_DISCOURSE.name,
) = NoteEntity(
    id = id, title = title, mdText = md, plainText = plainFromMarkdown(md),
    folderId = null, colorArgb = 0L, pinned = false, trashed = trashed,
    createdAt = createdAt, updatedAt = updatedAt,
    richHtml = "<p>$md</p>",
    discourseType = discourseType,
    lastSyncedAt = lastSyncedAt,
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
        assertEquals(
            original.copy(updatedAt = 0L, lastSyncedAt = 0L),
            restored.copy(updatedAt = 0L, lastSyncedAt = 0L),
        )
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

    // 8. Leitura atual do updatedAt (Long) e fallback para ausente.
    @Test
    fun cloudUpdatedAt_aceitaLongEIgnoraAusente() {
        assertEquals(123L, cloudUpdatedAt(mapOf("updatedAt" to 123L)))
        assertEquals(456L, cloudUpdatedAt(mapOf("updatedAt" to 456)))
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

    // ---------- M2: cópia de conflito ----------

    @Test
    fun conflito_localERemotoMudaram_criaCopiaECompoe() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Remota nova", "mdText" to "texto remoto",
            "richHtml" to "<p>texto remoto</p>", "trashed" to false,
            "createdAt" to 10L, "updatedAt" to 300L,
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", title = "Local editada", md = "texto local",
                updatedAt = 200L, lastSyncedAt = 100L),
        ))
        syncNotes(remote, dao, FakeTombstoneDao(), clock = { 9_000L })

        // Original recebe o remoto e marca o sync.
        val original = dao.rows.first { it.id == "n1" }
        assertEquals("Remota nova", original.title)
        assertEquals(300L, original.updatedAt)
        assertEquals(300L, original.lastSyncedAt)
        // Cópia preserva o texto local.
        val copy = dao.rows.first { it.id != "n1" }
        assertEquals("Local editada (conflito)", copy.title)
        assertEquals("texto local", copy.mdText)
        assertEquals(9_000L, copy.updatedAt)
        assertEquals(0L, copy.lastSyncedAt)
        // E a cópia será empurrada no próximo sync (não está na nuvem).
        assertNull(remote.doc("notes", copy.id))
    }

    @Test
    fun conflito_copiaTambemSecoesESubPontos() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Remota", "mdText" to "r", "richHtml" to "<p>r</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 300L,
        ))
        val noteDao = FakeNoteDao(listOf(
            note(id = "n1", updatedAt = 200L, lastSyncedAt = 100L),
        ))
        val sectionDao = FakeSectionDao(listOf(
            SpeechSectionEntity(
                id = "s1", noteId = "n1", order = 0, role = "BODY",
                title = "Ponto", minutes = 5, contentHtml = "<p>x</p>",
                bibleRefsJson = "[]", publicationRefsJson = "[]",
                methodPrinciple = null, createdAt = 1L, updatedAt = 1L,
            ),
        ))
        val subDao = FakeSubPointDao(listOf(
            SubPointEntity(
                id = "sp1", sectionId = "s1", order = 0,
                outlineText = "a) ideia", bibleRefsJson = "[]",
                publicationRefsJson = "[]", instruction = null,
                developedHtml = "<p>d</p>", createdAt = 1L, updatedAt = 1L,
            ),
        ))
        syncNotes(
            remote, noteDao, FakeTombstoneDao(), clock = { 9_000L },
            sectionDao = sectionDao, subPointDao = subDao,
        )

        val copy = noteDao.rows.first { it.id != "n1" }
        val copiedSections = sectionDao.rows.filter { it.noteId == copy.id }
        assertEquals(1, copiedSections.size)
        val copiedSubs = subDao.rows.filter { it.sectionId == copiedSections.single().id }
        assertEquals(1, copiedSubs.size)
        assertEquals("a) ideia", copiedSubs.single().outlineText)
        // IDs novos (não colidem com os originais).
        assertTrue(copiedSections.single().id != "s1")
        assertTrue(copiedSubs.single().id != "sp1")
    }

    @Test
    fun semConflito_soLocalMudou_empurraSemCopia() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Antiga", "mdText" to "a", "richHtml" to "<p>a</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 100L,
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", title = "Local editada", updatedAt = 200L, lastSyncedAt = 100L),
        ))
        syncNotes(remote, dao, FakeTombstoneDao())

        assertEquals(1, dao.rows.size)
        assertEquals("Local editada", remote.doc("notes", "n1")!!["title"])
        assertEquals(200L, dao.rows.single().lastSyncedAt)
    }

    @Test
    fun semConflito_soRemotoMudou_puxaSemCopia() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Remota", "mdText" to "r", "richHtml" to "<p>r</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 300L,
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", updatedAt = 100L, lastSyncedAt = 100L),
        ))
        syncNotes(remote, dao, FakeTombstoneDao())

        assertEquals(1, dao.rows.size)
        assertEquals("Remota", dao.rows.single().title)
        assertEquals(300L, dao.rows.single().lastSyncedAt)
    }

    @Test
    fun primeiroSync_naoCriaCopia() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Remota", "mdText" to "r", "richHtml" to "<p>r</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 300L,
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", updatedAt = 200L, lastSyncedAt = 0L),
        ))
        syncNotes(remote, dao, FakeTombstoneDao())

        assertEquals(1, dao.rows.size)
        assertEquals("Remota", dao.rows.single().title)
    }

    @Test
    fun pushMarcaLastSyncedAt() = runBlocking {
        val dao = FakeNoteDao(listOf(note(updatedAt = 1000L, lastSyncedAt = 0L)))
        syncNotes(remote, dao, FakeTombstoneDao())
        assertEquals(1000L, dao.rows.single().lastSyncedAt)
    }

    // ---------- M2: modo "Baixar" (push desabilitado) ----------

    @Test
    fun pushDesabilitado_naoEmpurraNotaLocal() = runBlocking {
        val dao = FakeNoteDao(listOf(note(id = "local", updatedAt = 5000L)))
        syncNotes(remote, dao, FakeTombstoneDao(), pushEnabled = false)
        assertTrue(remote.ids("notes").isEmpty())
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun pushDesabilitado_aindaPuxaRemoto() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Da nuvem", "mdText" to "x", "richHtml" to "<p>x</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 300L,
        ))
        val dao = FakeNoteDao()
        syncNotes(remote, dao, FakeTombstoneDao(), pushEnabled = false)
        assertEquals("Da nuvem", dao.rows.single().title)
        assertEquals(300L, dao.rows.single().lastSyncedAt)
    }

    @Test
    fun pushDesabilitado_naoPublicaTombstoneLocal() = runBlocking {
        val tombs = FakeTombstoneDao(listOf(TombstoneEntity("n1", "note", 9_000L)))
        val dao = FakeNoteDao()
        syncNotes(remote, dao, tombs, pushEnabled = false)
        assertTrue(remote.ids("tombstones").isEmpty())
    }

    @Test
    fun conflito_localMaisNovo_preservaRemotoEmCopia() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Remota", "mdText" to "texto remoto",
            "richHtml" to "<p>texto remoto</p>", "trashed" to false,
            "createdAt" to 10L, "updatedAt" to 300L,
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", title = "Local editada", md = "texto local",
                updatedAt = 400L, lastSyncedAt = 100L),
        ))
        syncNotes(remote, dao, FakeTombstoneDao(), clock = { 9_000L })

        // Local vence o original…
        assertEquals("Local editada", dao.rows.first { it.id == "n1" }.title)
        assertEquals("Local editada", remote.doc("notes", "n1")!!["title"])
        // …e o estado remoto é preservado na cópia.
        val copy = dao.rows.first { it.id != "n1" }
        assertEquals("Remota (conflito)", copy.title)
        assertEquals("texto remoto", copy.mdText)
        assertEquals(0L, copy.lastSyncedAt)
    }

    // ---------- M3: metadados de discurso no round-trip ----------

    @Test
    fun discourseType_sobeEPuxaSemVirarS34() = runBlocking {
        val original = note(
            id = "dt",
            updatedAt = 800L,
            discourseType = DiscourseType.MINISTRY_TALK.name,
        )
        syncNotes(remote, FakeNoteDao(listOf(original)), FakeTombstoneDao())

        assertEquals("MINISTRY_TALK", remote.doc("notes", "dt")!!["discourseType"])

        val pulled = FakeNoteDao()
        syncNotes(remote, pulled, FakeTombstoneDao())

        assertEquals("MINISTRY_TALK", pulled.rows.single().discourseType)
    }

    @Test
    fun discourseTypeRemotoIgual_localDefault_adotaSemBump() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Nota", "mdText" to "x", "richHtml" to "<p>x</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 1000L,
            "discourseType" to DiscourseType.MINISTRY_TALK.name,
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", updatedAt = 1000L, discourseType = DiscourseType.S34_DISCOURSE.name),
        ))

        syncNotes(remote, dao, FakeTombstoneDao())

        val local = dao.rows.single()
        assertEquals(DiscourseType.MINISTRY_TALK.name, local.discourseType)
        assertEquals(1000L, local.updatedAt)
    }

    @Test
    fun discourseTypeDivergenteMesmaRevisao_localVence() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Nota", "mdText" to "x", "richHtml" to "<p>x</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 1000L,
            "discourseType" to DiscourseType.MINISTRY_PART.name,
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", updatedAt = 1000L, discourseType = DiscourseType.MINISTRY_TALK.name),
        ))

        syncNotes(remote, dao, FakeTombstoneDao())

        // Regra documentada: com a mesma revisão e ambos com valor, local vence.
        assertEquals(DiscourseType.MINISTRY_TALK.name, dao.rows.single().discourseType)
        assertEquals(
            DiscourseType.MINISTRY_PART.name,
            remote.doc("notes", "n1")!!["discourseType"],
        )
    }

    @Test
    fun defaultNaoEhBackfillado_paraNaoBloquearOutroAparelho() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Nota", "mdText" to "x", "richHtml" to "<p>x</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 1000L,
        ))
        // B (default) sincroniza primeiro: não deve gravar S34 no remoto.
        syncNotes(remote, FakeNoteDao(listOf(note(id = "n1", updatedAt = 1000L))), FakeTombstoneDao())
        assertNull(remote.doc("notes", "n1")!!["discourseType"])

        // A (MINISTRY_TALK) sincroniza depois e consegue publicar o valor real.
        syncNotes(
            remote,
            FakeNoteDao(listOf(
                note(id = "n1", updatedAt = 1000L, discourseType = DiscourseType.MINISTRY_TALK.name),
            )),
            FakeTombstoneDao(),
        )
        assertEquals(
            DiscourseType.MINISTRY_TALK.name,
            remote.doc("notes", "n1")!!["discourseType"],
        )
    }

    @Test
    fun discourseTypeInvalidoRemoto_localValido_corrigeRemoto() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Nota", "mdText" to "x", "richHtml" to "<p>x</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 1000L,
            "discourseType" to "TIPO_QUE_NAO_EXISTE",
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", updatedAt = 1000L, discourseType = DiscourseType.MINISTRY_TALK.name),
        ))

        syncNotes(remote, dao, FakeTombstoneDao())

        assertEquals(DiscourseType.MINISTRY_TALK.name, dao.rows.single().discourseType)
        assertEquals(
            DiscourseType.MINISTRY_TALK.name,
            remote.doc("notes", "n1")!!["discourseType"],
        )
    }

    @Test
    fun backfill_discursoEmDocAntigoSemCampo() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Nota antiga", "mdText" to "x", "richHtml" to "<p>x</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 1000L,
        ))
        val dao = FakeNoteDao(listOf(
            note(id = "n1", updatedAt = 1000L, discourseType = DiscourseType.MINISTRY_TALK.name),
        ))

        syncNotes(remote, dao, FakeTombstoneDao())

        val doc = remote.doc("notes", "n1")!!
        assertEquals("MINISTRY_TALK", doc["discourseType"])
        assertEquals(1000L, doc["updatedAt"])
    }

    @Test
    fun discourseTypeInvalidoNoRemoto_caiNoDefault() = runBlocking {
        remote.set("notes", "n1", mapOf(
            "title" to "Da nuvem", "mdText" to "x", "richHtml" to "<p>x</p>",
            "trashed" to false, "createdAt" to 10L, "updatedAt" to 20L,
            "discourseType" to "TIPO_QUE_NAO_EXISTE",
        ))
        val dao = FakeNoteDao()
        syncNotes(remote, dao, FakeTombstoneDao())

        assertEquals(DiscourseType.S34_DISCOURSE.name, dao.rows.single().discourseType)
    }
}
