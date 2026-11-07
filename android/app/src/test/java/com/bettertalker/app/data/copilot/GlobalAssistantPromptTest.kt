package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.planning.GenerationProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt global: só títulos/janela curta, nunca dossiê/HTML de nota. */
class GlobalAssistantPromptTest {

    @Test
    fun incluiTitulosRecentesSemConteudoDeNota() {
        val prompt = GlobalAssistantPrompt.build(
            userText = "O que é uma boa introdução?",
            recentTitles = listOf("Esperança viva", "Cultivando o interesse"),
        )
        assertTrue(prompt.contains("Esperança viva"))
        assertTrue(prompt.contains("Cultivando o interesse"))
        assertTrue(prompt.contains("O que é uma boa introdução?"))
        assertFalse(prompt.contains("<p>"))
        assertFalse(prompt.contains("dossiê", ignoreCase = true))
    }

    @Test
    fun incluiJanelaCurtaDaConversa() {
        val prompt = GlobalAssistantPrompt.build(
            userText = "E na conclusão?",
            history = listOf(
                GlobalPromptTurn(fromMe = true, text = "Como começar?"),
                GlobalPromptTurn(fromMe = false, text = "Comece com uma pergunta."),
            ),
        )
        assertTrue(prompt.contains("Usuário: Como começar?"))
        assertTrue(prompt.contains("BT: Comece com uma pergunta."))
        assertTrue(prompt.contains("PEDIDO ATUAL:"))
    }

    @Test
    fun systemPromptExigeRespostaCurtaESemInvencao() {
        val system = GenerationPrompts
            .systemPromptFor(GenerationProfile.GLOBAL_ASSISTANT)
            .orEmpty()
        assertTrue(system.contains("4 frases"))
        assertTrue(system.contains("Não invente", ignoreCase = true))
        assertTrue(system.contains("títulos recentes"))
        assertTrue(system.contains("nunca finja", ignoreCase = true))
    }
}
