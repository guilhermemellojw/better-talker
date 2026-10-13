package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatRouter
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.repo.OutlineImportService
import com.bettertalker.app.data.util.ChatIntent
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.SectionPlan
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------- Fakes (padrão do projeto: in-memory, sem Room/Robolectric) ----------

private class TestSectionDao(var rows: List<SpeechSectionEntity> = emptyList()) : SpeechSectionDao {
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

private class TestSubPointDao(var rows: List<SubPointEntity> = emptyList()) : SubPointDao {
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

private class TestTransactionRunner : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}

// ---------- Fixtures ----------

private fun plan(
    title: String,
    minutes: Int,
    bible: List<String> = emptyList(),
) = SectionPlan(
    title = title, minutes = minutes, mainIdea = "Ideia central de $title.",
    bibleRefs = bible, publicationRefs = emptyList(), methodPrinciple = null,
)

private fun proposal(
    id: String,
    angle: OutlineAngle,
    title: String = "Título $id",
    summary: String = "Resumo do ângulo $angle.",
) = OutlineProposal(
    id = id, angle = angle, audience = Audience.GENERAL,
    title = title, summary = summary, totalMinutes = 30,
    sections = listOf(
        plan("Abertura", 10, listOf("João 3:16")),
        plan("Desenvolvimento", 12),
        plan("Aplicação", 8, listOf("Salmo 37:29")),
    ),
)

private fun multi(proposals: List<OutlineProposal>) = CopilotViewModel.MultiProposalUi(
    id = "mprop-1",
    theme = "fé",
    proposals = proposals,
)

/**
 * F5d (Modo B) — "me dá opções para X": comando sem sequestrar perguntas,
 * extração de tema, resultado do propose() (0/1/3), escolha → fluxo do #5b
 * e aceite com substituição real (fakes in-memory).
 */
class AnglesForThemeTest {

    // ---------- A/B: comando + tema ----------

    @Test
    fun comandoOpcoes_extraiTemaAposSobre() {
        val text = "me dá opções para um discurso sobre fé"
        assertTrue(ChatRouter.isAnglesForThemeCommand(text))
        assertEquals("fé", ChatIntent.extractThemeForAngles(text))
        val route = ChatRouter.route(text, null, null, null)
        assertTrue(route is ChatRouter.Route.AnglesForTheme)
        assertEquals("fé", (route as ChatRouter.Route.AnglesForTheme).theme)
    }

    @Test
    fun comandoAngulos_extraiTemaAposPara() {
        val text = "sugere ângulos para a esperança"
        assertTrue(ChatRouter.isAnglesForThemeCommand(text))
        assertEquals("a esperança", ChatIntent.extractThemeForAngles(text))
    }

    @Test
    fun perguntaAberta_naoSequestra() {
        val text = "o que você acha sobre fé?"
        assertFalse(ChatRouter.isAnglesForThemeCommand(text))
        assertNull(ChatIntent.extractThemeForAngles(text))
        val route = ChatRouter.route(text, null, null, null)
        assertFalse(
            route.toString(),
            route is ChatRouter.Route.AnglesForTheme,
        )
    }

    @Test
    fun demaisFormasDoComando_reconhecidas() {
        assertTrue(ChatRouter.isAnglesForThemeCommand("3 abordagens possíveis para a fé"))
        assertTrue(ChatRouter.isAnglesForThemeCommand("que ângulos posso usar para a esperança"))
        assertTrue(ChatRouter.isAnglesForThemeCommand("como posso falar sobre o perdão"))
        assertEquals("o perdão", ChatIntent.extractThemeForAngles("como posso falar sobre o perdão"))
        assertEquals("a fé", ChatIntent.extractThemeForAngles("3 abordagens possíveis para a fé"))
        assertFalse(ChatRouter.isAnglesForThemeCommand("qual a melhor opção para o tema?"))
    }

    // ---------- C: resultado do propose() ----------

    @Test
    fun semTema_erroPedeTema() {
        val text = "me dá opções"
        assertTrue(ChatRouter.isAnglesForThemeCommand(text))
        assertNull(ChatIntent.extractThemeForAngles(text))
        val outcome = buildAnglesOutcome(null, emptyList())
        assertTrue(outcome is AnglesOutcome.Error)
        assertEquals(ANGLES_NEEDS_THEME_MESSAGE, (outcome as AnglesOutcome.Error).message)
        assertTrue(ANGLES_NEEDS_THEME_MESSAGE.contains("Sobre qual tema?"))
    }

    @Test
    fun tresValidas_criaMultiCom3() {
        val outcome = buildAnglesOutcome(
            "fé",
            listOf(
                proposal("p1", OutlineAngle.DOCTRINAL),
                proposal("p2", OutlineAngle.PRACTICAL),
                proposal("p3", OutlineAngle.NARRATIVE),
            ),
            id = "mprop-1",
        )
        assertTrue(outcome is AnglesOutcome.Ui)
        val ui = (outcome as AnglesOutcome.Ui).ui
        assertEquals(3, ui.proposals.size)
        assertEquals("fé", ui.theme)
        assertNull(ui.chosen)
        assertNull(ui.notice)
    }

    @Test
    fun umaValida_criaMultiCom1() {
        val outcome = buildAnglesOutcome(
            "esperança",
            listOf(proposal("p2", OutlineAngle.PRACTICAL)),
            id = "mprop-2",
        )
        assertTrue(outcome is AnglesOutcome.Ui)
        assertEquals(1, (outcome as AnglesOutcome.Ui).ui.proposals.size)
        assertEquals("esperança", outcome.ui.theme)
    }

    @Test
    fun zeroValidas_erroNoChat() {
        val outcome = buildAnglesOutcome("fé", emptyList())
        assertTrue(outcome is AnglesOutcome.Error)
        assertEquals(ANGLES_EMPTY_MESSAGE, (outcome as AnglesOutcome.Error).message)
    }

    // ---------- D/E: escolha → NoteStructureProposalUi → aceite ----------

    @Test
    fun escolher1_criaNoteStructureProposalComAMesmaProposta() {
        val p2 = proposal("p2", OutlineAngle.PRACTICAL, title = "Ângulo prático")
        val ui = multi(listOf(proposal("p1", OutlineAngle.DOCTRINAL), p2))
        val chosen = ui.copy(chosen = "p2")
        val nsp = noteStructureProposalFromChoice(
            ui = chosen,
            proposalId = "p2",
            noteId = "n1",
            currentSectionCount = 2,
            noteBaseHash = "hash-base",
            id = "nsp-1",
        )
        assertEquals("nsp-1", nsp!!.id)
        assertEquals("n1", nsp.noteId)
        assertEquals("Ângulo prático", nsp.title)
        assertEquals(p2.summary, nsp.summary)
        assertEquals(p2.totalMinutes, nsp.totalMinutes)
        assertEquals(p2.sections, nsp.sections)
        assertEquals(2, nsp.currentSectionCount)
        assertFalse(nsp.applied)
        assertNull(nsp.notice)
        // Id inexistente → null (nenhum cartão criado).
        assertNull(
            noteStructureProposalFromChoice(chosen, "p9", "n1", 2, "hash-base"),
        )
    }

    @Test
    fun aceitarEscolhida_substituiViaPersist() = runBlocking {
        val old = SpeechSectionEntity(
            id = "old-1", noteId = "n1", order = 0, role = SectionRole.BODY.name,
            title = "Seção antiga", minutes = 5, contentHtml = "<p>antigo</p>",
            bibleRefsJson = "[]", publicationRefsJson = "[]", methodPrinciple = null,
            createdAt = 1L, updatedAt = 1L,
        )
        val sectionDao = TestSectionDao(rows = listOf(old))
        val service = OutlineImportService(TestTransactionRunner(), sectionDao, TestSubPointDao())

        val ui = multi(listOf(proposal("p1", OutlineAngle.DOCTRINAL)))
        val nsp = noteStructureProposalFromChoice(
            ui = ui.copy(chosen = "p1"),
            proposalId = "p1",
            noteId = "n1",
            currentSectionCount = 0,
            noteBaseHash = "hash-base",
            id = "nsp-1",
        )!!
        val decision = decideNoteStructureAccept(nsp, nsp.noteBaseHash)
        assertTrue(decision is NoteStructureAcceptDecision.Apply)
        val total = service.persist(
            (decision as NoteStructureAcceptDecision.Apply).conversion,
            DiscourseType.S34_DISCOURSE,
        )
        // 3 seções do plano + intro + conclusão; a antiga saiu.
        assertEquals(5, total)
        assertEquals(5, sectionDao.rows.size)
        assertTrue(sectionDao.rows.none { it.id == "old-1" })
        assertEquals(
            listOf("Abertura", "Desenvolvimento", "Aplicação"),
            sectionDao.rows.filter { it.role == SectionRole.BODY.name }
                .sortedBy { it.order }.map { it.title },
        )
        // Aceite stale segue bloqueando (reuso do #5b).
        assertEquals(
            NoteStructureAcceptDecision.Stale,
            decideNoteStructureAccept(nsp, currentHash = "outro-hash"),
        )
    }
}
