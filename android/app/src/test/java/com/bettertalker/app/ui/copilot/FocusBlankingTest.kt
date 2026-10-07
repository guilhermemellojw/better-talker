package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 — anti-apagamento do foco em `answerRemote`.
 *
 * Com alvo empurrado mas sem dossiê, `text`/`blockTitle` legados devem ser
 * mantidos (nunca zerados). Puro, sem Room/LLM.
 */
class FocusBlankingTest {

    @Test
    fun blank_comAlvoEDossie_retornaTrue() {
        // Caso normal: push + dossiê → foco legado recua, dossiê manda.
        assertTrue(
            shouldBlankFocusForTarget(
                hasTarget = true,
                contextBlock = "## SEÇÃO ATUAL\nTítulo: Fé",
            )
        )
    }

    @Test
    fun blank_comAlvoSemDossie_retornaFalse() {
        // O caso do bug: push sem dossiê → manter text/blockTitle legados.
        assertFalse(shouldBlankFocusForTarget(hasTarget = true, contextBlock = null))
        assertFalse(shouldBlankFocusForTarget(hasTarget = true, contextBlock = ""))
    }

    @Test
    fun blank_semAlvo_retornaFalse() {
        // Chat genérico: nunca zera, com ou sem bloco de contexto.
        assertFalse(shouldBlankFocusForTarget(hasTarget = false, contextBlock = null))
        assertFalse(
            shouldBlankFocusForTarget(
                hasTarget = false,
                contextBlock = "## SEÇÃO ATUAL\nTítulo: Fé",
            )
        )
    }
}

class PushedFirstFocusTest {

    @Test
    fun foco_pushVenceSelecaoEBloco() {
        // O caso do bug: push aponta o tópico certo, legados divergem.
        assertEquals(
            "Fé que age",
            pushedFirstFocus(
                pushedTitle = "Fé que age",
                selection = "trecho selecionado",
                activeBlockTitle = "Outro ponto",
                noteBody = "nota inteira",
            )
        )
    }

    @Test
    fun foco_semPush_mantemOrdemLegada() {
        assertEquals(
            "trecho selecionado",
            pushedFirstFocus(
                pushedTitle = null,
                selection = "trecho selecionado",
                activeBlockTitle = "Outro ponto",
                noteBody = "nota inteira",
            )
        )
        assertEquals(
            "Outro ponto",
            pushedFirstFocus(
                pushedTitle = null,
                selection = "",
                activeBlockTitle = "Outro ponto",
                noteBody = "nota inteira",
            )
        )
        assertEquals(
            "nota inteira",
            pushedFirstFocus(
                pushedTitle = null,
                selection = "",
                activeBlockTitle = null,
                noteBody = "nota inteira",
            )
        )
    }

    @Test
    fun foco_pushEmBranco_ignorado() {
        assertEquals(
            "trecho selecionado",
            pushedFirstFocus(
                pushedTitle = "  ",
                selection = "trecho selecionado",
                activeBlockTitle = null,
                noteBody = "nota inteira",
            )
        )
    }
}

class StructuralHintTest {

    @Test
    fun hint_pushVenceBlocoESelecao() {
        assertEquals(
            "Fé que age",
            structuralHint(
                pushedTitle = "Fé que age",
                activeBlockTitle = "Outro ponto",
                selection = "trecho",
            )
        )
    }

    @Test
    fun hint_semPush_mantemLegado() {
        assertEquals(
            "Outro ponto",
            structuralHint(pushedTitle = null, activeBlockTitle = "Outro ponto", selection = "trecho")
        )
        assertEquals(
            "trecho",
            structuralHint(pushedTitle = null, activeBlockTitle = null, selection = "trecho")
        )
    }
}
