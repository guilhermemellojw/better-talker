package com.bettertalker.app.ui.copilot

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
