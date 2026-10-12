package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Inversão de contexto (correção): a prosa em foco SEMPRE vai ao LLM,
 * com ou sem alvo empurrado — o dossiê (metadados) viaja em `contextBlock`,
 * nunca no lugar da prosa. Puro, sem Room/LLM.
 */
class FocusTextForLlmTest {

    @Test
    fun focus_comAlvoEDossie_mantemProsa() {
        // O caso do bug: push + dossiê zerava o texto do bloco — o modelo
        // via só o plano e "revise esta seção" saía genérico.
        assertEquals(
            "Texto da seção em foco.",
            focusTextForLlm("Texto da seção em foco."),
        )
    }

    @Test
    fun focus_comAlvoSemDossie_mantemProsa() {
        assertEquals(
            "Texto da seção em foco.",
            focusTextForLlm("Texto da seção em foco."),
        )
    }

    @Test
    fun focus_semAlvo_mantemProsa() {
        // Chat genérico: comportamento inalterado.
        assertEquals(
            "Corpo da nota.",
            focusTextForLlm("Corpo da nota."),
        )
    }

    @Test
    fun focus_vazioContinuaVazio() {
        // Nunca inventa: sem prosa, entra vazio (o dossiê/RAG seguem no payload).
        assertEquals("", focusTextForLlm(""))
        assertEquals("   ", focusTextForLlm("   "))
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
