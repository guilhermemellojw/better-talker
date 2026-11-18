package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
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
}
