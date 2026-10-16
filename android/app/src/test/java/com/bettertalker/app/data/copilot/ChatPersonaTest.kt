package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #18-fix — persona no system do chat (estável para cache). Puro. */
class ChatPersonaTest {

    private fun system(message: String = "melhore a transição") = buildDeepSeekPrompts(
        message = message,
        history = emptyList(),
        isFirstMessage = true,
        pack = ContextPack(emptyList(), emptyList()),
        blockTitle = null,
        blockMinutes = null,
        blockText = "x",
    ).system

    @Test
    fun systemDoChatContemAPersona() {
        val s = system()
        assertTrue(s.startsWith(CHAT_PERSONA))
        assertTrue(s.contains("Testemunhas de Jeová"))
        assertTrue(s.contains("Evite formalidade excessiva"))
    }

    @Test
    fun personaEhDeterministicaParaCache() {
        // Sem interpolação dinâmica: idêntica para mensagens diferentes.
        val a = system("o que acha?")
        val b = system("desenvolva esse ponto para 6 minutos")
        assertTrue(a.startsWith(CHAT_PERSONA))
        assertTrue(b.startsWith(CHAT_PERSONA))
        assertFalse(CHAT_PERSONA.contains("$"))
    }

    @Test
    fun minimalSystemPromptIntocado() {
        assertTrue(MINIMAL_SYSTEM_PROMPT.contains("Você é um copiloto de oratória."))
        assertTrue(
            MINIMAL_SYSTEM_PROMPT.contains(
                "Não encontrei suporte suficiente nas fontes disponíveis."
            )
        )
        assertFalse(MINIMAL_SYSTEM_PROMPT.contains(CHAT_PERSONA))
    }
}
