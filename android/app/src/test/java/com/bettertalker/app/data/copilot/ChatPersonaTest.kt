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
        assertTrue(s.startsWith(CHAT_PERSONA_FREE))
        assertTrue(s.contains("Testemunhas de Jeová"))
        assertTrue(s.contains("Evite formalidade excessiva"))
        assertTrue(s.contains("SOMENTE quando o usuário pedir"))
        assertFalse(CHAT_PERSONA.contains("SOMENTE quando o usuário pedir"))
    }

    @Test
    fun personaEhDeterministicaParaCache() {
        // Sem interpolação dinâmica: idêntica para mensagens diferentes.
        val a = system("o que acha?")
        val b = system("desenvolva esse ponto para 6 minutos")
        assertTrue(a.startsWith(CHAT_PERSONA_FREE))
        assertTrue(b.startsWith(CHAT_PERSONA_FREE))
        assertFalse(CHAT_PERSONA.contains("$"))
    }

    @Test
    fun personaPreservaTermosBiblicos() {
        // exp/chat-quality (2026-10-21): no A/B o modelo trocou "Jeová" por
        // "Deus" na introdução. A persona do chat agora proíbe a troca.
        assertTrue(CHAT_PERSONA_FREE.contains("Jeová"))
        assertTrue(CHAT_PERSONA_FREE.contains("nunca troque 'Jeová' por 'Deus'"))
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

    @Test
    fun linhaDeVozDaGeracao_emendaNaPersona() {
        // P0: a linha de voz dos fluxos de geração começa com quebra de linha
        // para emendar direto após a persona.
        assertTrue(GENERATION_VOICE_LINE.startsWith("\n"))
        assertTrue(GENERATION_VOICE_LINE.contains("tom caloroso"))
        assertTrue(GENERATION_VOICE_LINE.contains("ritmo natural de fala"))
        assertTrue(GENERATION_VOICE_LINE.contains("demonstrações"))
    }
}
