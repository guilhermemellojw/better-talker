package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.QuickAction
import com.bettertalker.app.data.edit.CopilotEditProposal
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.SectionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleModeGuidanceTest {

    private fun guidance(
        sourcesBlocked: Boolean = false,
        hasError: Boolean = false,
        hasProposal: Boolean = false,
        isGenerating: Boolean = false,
        hasNextStep: Boolean = false,
        isEmptyConversation: Boolean = false,
    ) = simpleModeGuidance(
        simpleMode = true,
        sourcesBlocked = sourcesBlocked,
        hasError = hasError,
        hasProposal = hasProposal,
        isGenerating = isGenerating,
        hasNextStep = hasNextStep,
        isEmptyConversation = isEmptyConversation,
    )

    @Test
    fun modoPadraoNaoTemOrientacao() {
        val g = simpleModeGuidance(
            simpleMode = false,
            sourcesBlocked = true,
            hasError = true,
            hasProposal = true,
            isGenerating = true,
            hasNextStep = true,
            isEmptyConversation = true,
        )
        assertEquals(SimpleGuidanceKind.NONE, g.kind)
    }

    @Test
    fun prioridadeFontesAntesDeTudo() {
        assertEquals(
            SimpleGuidanceKind.SOURCES_BLOCKED,
            guidance(
                sourcesBlocked = true,
                hasError = true,
                hasProposal = true,
                isGenerating = true,
                hasNextStep = true,
                isEmptyConversation = true,
            ).kind,
        )
    }

    @Test
    fun prioridadeErroAntesDePropostaGeracaoEProximoPasso() {
        assertEquals(
            SimpleGuidanceKind.ERROR,
            guidance(
                hasError = true,
                hasProposal = true,
                isGenerating = true,
                hasNextStep = true,
                isEmptyConversation = true,
            ).kind,
        )
    }

    @Test
    fun prioridadePropostaAntesDeGeracaoEProximoPasso() {
        assertEquals(
            SimpleGuidanceKind.PROPOSAL,
            guidance(
                hasProposal = true,
                isGenerating = true,
                hasNextStep = true,
                isEmptyConversation = true,
            ).kind,
        )
    }

    @Test
    fun prioridadeGeracaoAntesDeProximoPasso() {
        assertEquals(
            SimpleGuidanceKind.GENERATING,
            guidance(
                isGenerating = true,
                hasNextStep = true,
                isEmptyConversation = true,
            ).kind,
        )
    }

    @Test
    fun prioridadeProximoPassoAntesDeVazio() {
        assertEquals(
            SimpleGuidanceKind.NEXT_STEP,
            guidance(hasNextStep = true, isEmptyConversation = true).kind,
        )
    }

    @Test
    fun conversaVaziaSemPendencias() {
        assertEquals(
            SimpleGuidanceKind.EMPTY,
            guidance(isEmptyConversation = true).kind,
        )
    }

    @Test
    fun estadoNormalSemOrientacao() {
        assertEquals(SimpleGuidanceKind.NONE, guidance().kind)
    }

    @Test
    fun atalhosLocaisPriorizamProximoPassoEDesenvolvimento() {
        val shortcuts = simpleModeLocalShortcuts(
            listOf(
                "Mostrar fontes",
                "Resumir",
                "Diagnóstico do esboço",
                "Como desenvolver este ponto",
                "Próximo passo",
            ),
        )
        assertEquals("Próximo passo", shortcuts.first())
        assertTrue(shortcuts.indexOf("Como desenvolver este ponto") < shortcuts.indexOf("Mostrar fontes"))
    }

    @Test
    fun limiteDeAtalhosDoModoSimples() {
        assertEquals(3, SIMPLE_MODE_SHORTCUT_LIMIT)
    }

    private fun proposal(applied: Boolean = false, notice: String? = null) =
        CopilotViewModel.ProposalUi(
            proposal = CopilotEditProposal(
                id = "p",
                mode = EditProposalMode.IMPROVE,
                operations = emptyList(),
            ),
            focusText = "texto",
            applied = applied,
            notice = notice,
        )

    private fun outlineProposal() = OutlineProposal(
        id = "o",
        angle = OutlineAngle.PRACTICAL,
        audience = Audience.GENERAL,
        title = "Título",
        summary = "Resumo",
        totalMinutes = 5,
        sections = emptyList(),
    )

    private fun sectionPlan() = SectionPlan(
        title = "Título",
        minutes = 5,
        mainIdea = "Ideia",
    )

    private fun structureProposal(applied: Boolean = false, notice: String? = null) =
        CopilotViewModel.StructureProposalUi(
            id = "s",
            sectionId = "sec",
            proposal = outlineProposal(),
            plan = sectionPlan(),
            summary = "Resumo",
            sectionBaseHash = "hash",
            applied = applied,
            notice = notice,
        )

    private fun noteStructureProposal(applied: Boolean = false, notice: String? = null) =
        CopilotViewModel.NoteStructureProposalUi(
            id = "n",
            noteId = "note",
            title = "Título",
            summary = "Resumo",
            totalMinutes = 5,
            sections = emptyList(),
            noteBaseHash = "hash",
            applied = applied,
            notice = notice,
        )

    private fun multiProposal(chosen: String? = null, notice: String? = null) =
        CopilotViewModel.MultiProposalUi(
            id = "m",
            theme = "Tema",
            proposals = emptyList(),
            chosen = chosen,
            notice = notice,
        )

    @Test
    fun propostaDeTextoPendenteEAplicadaOuComAviso() {
        assertTrue(proposal().isPendingProposal())
        assertFalse(proposal(applied = true).isPendingProposal())
        assertFalse(proposal(notice = "aplicada").isPendingProposal())
    }

    @Test
    fun propostaDeEstruturaPendenteEAplicadaOuComAviso() {
        assertTrue(structureProposal().isPendingProposal())
        assertFalse(structureProposal(applied = true).isPendingProposal())
        assertFalse(structureProposal(notice = "rejeitada").isPendingProposal())
    }

    @Test
    fun propostaDeNotaPendenteEAplicadaOuComAviso() {
        assertTrue(noteStructureProposal().isPendingProposal())
        assertFalse(noteStructureProposal(applied = true).isPendingProposal())
        assertFalse(noteStructureProposal(notice = "rejeitada").isPendingProposal())
    }

    @Test
    fun multiPropostaPendenteEscolhidaOuComAviso() {
        assertTrue(multiProposal().isPendingProposal())
        assertFalse(multiProposal(chosen = "a").isPendingProposal())
        assertFalse(multiProposal(notice = "sem opções").isPendingProposal())
    }

    @Test
    fun hasPendingProposalConsideraSomenteEstadosReais() {
        assertFalse(
            hasPendingProposal(
                proposal = proposal(applied = true),
                structureProposal = structureProposal(notice = "ok"),
                noteStructureProposal = noteStructureProposal(applied = true),
                multiProposal = multiProposal(chosen = "a"),
            ),
        )
        assertTrue(
            hasPendingProposal(
                proposal = proposal(),
                structureProposal = null,
                noteStructureProposal = null,
                multiProposal = null,
            ),
        )
    }

    @Test
    fun resolvedProposalNaoImpedeProximoPasso() {
        val g = simpleModeGuidance(
            simpleMode = true,
            sourcesBlocked = false,
            hasError = false,
            hasProposal = false,
            isGenerating = false,
            hasNextStep = true,
            isEmptyConversation = false,
        )
        assertEquals(SimpleGuidanceKind.NEXT_STEP, g.kind)
    }

    @Test
    fun planoDeAtalhosRespeitaTresItensEVerOutrasOpcoes() {
        val plan = simpleModeShortcutPlan(
            quickActions = listOf(
                QuickAction("a", "Melhorar este ponto", "Melhore este ponto."),
                QuickAction("b", "Deixar mais natural", "Deixe mais natural."),
                QuickAction("c", "Criar ilustração", "Crie uma ilustração."),
            ),
            localActions = listOf("Próximo passo", "Mostrar fontes"),
        )
        assertEquals(
            listOf("Melhorar este ponto", "Próximo passo", "Necessidades Locais"),
            plan.labels,
        )
        assertTrue(plan.hasMore)
    }

    @Test
    fun planoSemAtalhosSuficientesNaoMostraVerOutrasOpcoes() {
        val plan = simpleModeShortcutPlan(
            quickActions = emptyList(),
            localActions = listOf("Próximo passo"),
        )
        assertEquals(listOf("Próximo passo", "Necessidades Locais"), plan.labels)
        assertFalse(plan.hasMore)
    }
}
