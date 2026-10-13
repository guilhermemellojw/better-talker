package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatRouter
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.repo.OutlineImportService
import com.bettertalker.app.data.util.PublicationDetector
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.SectionPlan
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.ui.editor.DraftTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------- Fakes (padrão do projeto: in-memory, sem Room/Robolectric) ----------

private class FakeSectionDao(var rows: List<SpeechSectionEntity> = emptyList()) : SpeechSectionDao {
    override suspend fun forNote(noteId: String): List<SpeechSectionEntity> =
        rows.filter { it.noteId == noteId }.sortedBy { it.order }
    override fun observeForNote(noteId: String): Flow<List<SpeechSectionEntity>> =
        flowOf(rows.filter { it.noteId == noteId }.sortedBy { it.order })
    override suspend fun get(id: String): SpeechSectionEntity? = rows.firstOrNull { it.id == id }
    override suspend fun upsert(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id } + section
    }
    override suspend fun upsertAll(sections: List<SpeechSectionEntity>) {
        sections.forEach { upsert(it) }
    }
    override suspend fun delete(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id }
    }
    override suspend fun deleteForNote(noteId: String) {
        rows = rows.filter { it.noteId != noteId }
    }
}

private class FakeSubPointDao(var rows: List<SubPointEntity> = emptyList()) : SubPointDao {
    override suspend fun forSection(sectionId: String): List<SubPointEntity> =
        rows.filter { it.sectionId == sectionId }.sortedBy { it.order }
    override suspend fun get(id: String): SubPointEntity? = rows.firstOrNull { it.id == id }
    override suspend fun forSections(sectionIds: List<String>): List<SubPointEntity> =
        rows.filter { it.sectionId in sectionIds }.sortedWith(compareBy({ it.sectionId }, { it.order }))
    override fun observeForSections(sectionIds: List<String>): Flow<List<SubPointEntity>> =
        flowOf(rows.filter { it.sectionId in sectionIds }
            .sortedWith(compareBy({ it.sectionId }, { it.order })))
    override suspend fun upsert(subPoint: SubPointEntity) {
        rows = rows.filter { it.id != subPoint.id } + subPoint
    }
    override suspend fun upsertAll(subPoints: List<SubPointEntity>) {
        subPoints.forEach { upsert(it) }
    }
    override suspend fun delete(subPoint: SubPointEntity) {
        rows = rows.filter { it.id != subPoint.id }
    }
    override suspend fun deleteForSection(sectionId: String) {
        rows = rows.filter { it.sectionId != sectionId }
    }
}

private class FakeTransactionRunner : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}

// ---------- Fixtures ----------

private val SOURCE = """
    O que a Bíblia promete para o futuro? (w13 15/5)
    Jeová Deus prometeu um paraíso por meio de Jesus.
    [Leia João 3:16.]
    Por que podemos confiar nessa promessa? (g 11/06)
    [Leia Salmo 37:29.]
    A promessa se cumprirá. (bh 109)
""".trimIndent()

private fun sectionDomain(
    title: String = "Seção",
    contentHtml: String = "",
) = SpeechSection(
    id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
    title = title, minutes = 5, contentHtml = contentHtml,
    bibleRefs = emptyList(), publicationRefs = emptyList(),
    methodPrinciple = null, createdAt = 1L, updatedAt = 1L,
)

private fun plan(
    title: String,
    minutes: Int,
    bible: List<String> = emptyList(),
) = SectionPlan(
    title = title, minutes = minutes, mainIdea = "Ideia central de $title.",
    bibleRefs = bible, publicationRefs = emptyList(), methodPrinciple = null,
)

private fun proposal() = OutlineProposal(
    id = "p1", angle = OutlineAngle.PRACTICAL, audience = Audience.GENERAL,
    title = "Uma promessa segura", summary = "Resumo do artigo colado.",
    totalMinutes = 30,
    sections = listOf(
        plan("O futuro que a Bíblia promete", 10, listOf("João 3:16")),
        plan("Por que confiar na promessa", 12, listOf("Salmo 37:29")),
        plan("Como agir agora", 8),
    ),
)

private fun uiFor(
    proposal: OutlineProposal = proposal(),
    noteBaseHash: String = "hash-base",
) = CopilotViewModel.NoteStructureProposalUi(
    id = "nsp-1",
    noteId = "n1",
    title = proposal.title,
    summary = proposal.summary,
    totalMinutes = proposal.totalMinutes,
    sections = proposal.sections,
    hints = listOf("refs de publicação (3)"),
    currentSectionCount = 2,
    noteBaseHash = noteBaseHash,
)

/**
 * F5b (Modo C) — estruturar texto colado de publicação: comando, alvo,
 * request (refs extraídas do texto), proposta pendente, aceite (stale +
 * persist via OutlineImportService), rejeição sem escrita e conversão pura.
 */
class StructureFromPastedTest {

    // ---------- B: comando no chat ----------

    @Test
    fun comando_formasReconhecidas() {
        val commands = listOf(
            "estruturar como esboço",
            "estrutura como esboço",
            "transforma em esboço",
            "transformar em esboço",
            "organiza em seções",
            "organizar como discurso",
            "isto vira um discurso",
        )
        for (c in commands) {
            assertTrue(c, ChatRouter.isStructureFromPastedCommand(c))
        }
    }

    @Test
    fun comando_naoSequestraPerguntasNemConversa() {
        val others = listOf(
            "",
            "olá, tudo bem?",
            "qual a estrutura do discurso?",
            "estrutura do discurso está boa",
            "me fale sobre discursos",
        )
        for (c in others) {
            assertFalse(c, ChatRouter.isStructureFromPastedCommand(c))
        }
        val route = ChatRouter.route("qual a estrutura do discurso?", null, null, null)
        assertFalse(
            route.toString(),
            route is ChatRouter.Route.StructureFromPasted,
        )
        val ok = ChatRouter.route("estruturar como esboço", null, null, null)
        assertTrue(ok is ChatRouter.Route.StructureFromPasted)
    }

    // ---------- 6. seção sem texto → pede para colar ----------

    @Test
    fun semConteudo_orientaColar() {
        assertEquals(STRUCTURE_PASTED_NEEDS_SECTION_MESSAGE, structurePastedTargetError(null))
        assertEquals(
            STRUCTURE_PASTED_NEEDS_SECTION_MESSAGE,
            structurePastedTargetError(DraftTarget.SubPoint("s1", "sp1")),
        )
        assertNull(structurePastedTargetError(DraftTarget.Section("s1")))
        assertEquals(STRUCTURE_PASTED_EMPTY_MESSAGE, structurePastedEmptyError("   "))
        assertNull(structurePastedEmptyError("texto colado"))
        assertTrue(STRUCTURE_PASTED_EMPTY_MESSAGE.contains("Cole o texto"))
    }

    // ---------- 7. seção com texto → request + proposta pendente ----------

    @Test
    fun secaoComTexto_montaPropostaPendente() {
        val request = buildStructureFromPastedRequest(
            sectionDomain(title = "Seção com artigo"),
            SOURCE,
            id = "req-1",
        )
        assertEquals("req-1", request.id)
        assertEquals("Seção com artigo", request.theme)
        assertEquals(STRUCTURE_PASTED_TOTAL_MINUTES, request.totalMinutes)
        assertEquals(Audience.GENERAL, request.audience)
        assertEquals(SOURCE, request.sourceContent)
        assertTrue(request.bibleRefs.toString(), request.bibleRefs.any { it.endsWith("3:16") })
        assertTrue(request.publicationRefs.toString(), request.publicationRefs.size >= 3)

        val signal = PublicationDetector.looksLikePublication(SOURCE)
        assertTrue(signal.hints.isNotEmpty())

        val ui = uiFor()
        assertFalse(ui.applied)
        assertNull(ui.notice)
        assertEquals(3, ui.sections.size)
        assertEquals(ui.sections, noteProposalOf(ui).sections)
        assertEquals("hash-base", ui.noteBaseHash)
    }

    // ---------- 8. aceitar substitui as seções (persist) ----------

    @Test
    fun aceitar_substituiSecoesViaPersist() = runBlocking {
        val old = SpeechSectionEntity(
            id = "old-1", noteId = "n1", order = 0, role = SectionRole.BODY.name,
            title = "Seção antiga", minutes = 5, contentHtml = "<p>antigo</p>",
            bibleRefsJson = "[]", publicationRefsJson = "[]", methodPrinciple = null,
            createdAt = 1L, updatedAt = 1L,
        )
        val sectionDao = FakeSectionDao(rows = listOf(old))
        val subPointDao = FakeSubPointDao()
        val service = OutlineImportService(FakeTransactionRunner(), sectionDao, subPointDao)

        val ui = uiFor()
        val decision = decideNoteStructureAccept(ui, ui.noteBaseHash)
        assertTrue(decision is NoteStructureAcceptDecision.Apply)
        val conversion = (decision as NoteStructureAcceptDecision.Apply).conversion
        service.persist(conversion, DiscourseType.S34_DISCOURSE)

        val stored = sectionDao.rows.filter { it.noteId == "n1" }.sortedBy { it.order }
        // Substituição: a antiga saiu; intro + N bodies + conclusão entraram.
        assertTrue(stored.none { it.id == "old-1" })
        assertEquals(ui.sections.size + 2, stored.size)
        assertEquals(
            ui.sections.map { it.title },
            stored.filter { it.role == SectionRole.BODY.name }.map { it.title },
        )
        assertEquals(SectionRole.INTRO.name, stored.first().role)
        assertEquals(SectionRole.CONCLUSION.name, stored.last().role)
        // Corpo do plano vira sub-pontos (ideia central + refs).
        val bodies = stored.filter { it.role == SectionRole.BODY.name }
        assertTrue(bodies.all { b -> subPointDao.rows.any { it.sectionId == b.id } })
    }

    // ---------- 9. nota alterada → obsoleta, não aplica ----------

    @Test
    fun notaAlterada_propostaObsoletaNaoAplica() {
        val ui = uiFor(noteBaseHash = "hash-base")
        assertEquals(
            NoteStructureAcceptDecision.Stale,
            decideNoteStructureAccept(ui, currentHash = "hash-outro"),
        )
        // Proposta corrompida no meio do caminho: inválida, não aplica.
        assertEquals(
            NoteStructureAcceptDecision.Invalid,
            decideNoteStructureAccept(ui.copy(summary = " "), ui.noteBaseHash),
        )
        // Hash gate puro: nota com seções diferentes → hash diferente.
        val a = noteStructureHash("Nota", listOf(sectionDomain(title = "A")))
        val b = noteStructureHash("Nota", listOf(sectionDomain(title = "B")))
        assertNotEquals(a, b)
        val apply = decideNoteStructureAccept(ui, ui.noteBaseHash)
        assertTrue(apply is NoteStructureAcceptDecision.Apply)
        assertEquals(
            ui.sections.size,
            (apply as NoteStructureAcceptDecision.Apply).conversion.bodies.size,
        )
    }

    // ---------- 10. rejeitar não escreve nada ----------

    @Test
    fun rejeitar_naoEscreveNada() {
        // O caminho de rejeição é só UI (o VM nunca chama persist): aqui
        // garantimos que o estado rejeitado permanece pendente-nunca-aplicado.
        val rejected = uiFor().copy(notice = NOTE_STRUCTURE_REJECTED_NOTICE)
        assertFalse(rejected.applied)
        assertEquals(NOTE_STRUCTURE_REJECTED_NOTICE, rejected.notice)
        assertTrue(NOTE_STRUCTURE_REJECTED_NOTICE.contains("nada foi alterado"))
    }
}
