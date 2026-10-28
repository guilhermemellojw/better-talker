package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import org.junit.Assert.assertEquals
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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    @Test
    fun systemDoChatContemAPersona() {
        val s = system()
        assertTrue(s.startsWith(CHAT_PERSONA_FREE))
        assertTrue(s.contains("Testemunhas de Jeová"))
        assertTrue(s.contains("Evite formalidade excessiva"))
        assertTrue(s.contains(CARD_FIRST_RULE))
        assertEquals(1, occurrences(s, CARD_FIRST_RULE))
        assertFalse(CHAT_PERSONA_FREE.contains(CARD_FIRST_RULE))
        assertFalse(CHAT_PERSONA.contains(CARD_FIRST_RULE))
    }

    @Test
    fun regraDoCardViveSoNaCaudaDoChat() {
        assertTrue(chatTailInstructions().contains(CARD_FIRST_RULE))
        assertEquals(1, occurrences(chatTailInstructions(), CARD_FIRST_RULE))
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
    fun chat_persona_free_nao_menciona_irmao() {
        assertFalse(CHAT_PERSONA_FREE.contains("irmão"))
        assertFalse(CHAT_PERSONA_FREE.contains("irmã"))
    }

    @Test
    fun chat_persona_free_usa_orientador() {
        assertTrue(CHAT_PERSONA_FREE.contains("orientador experiente"))
        assertFalse(CHAT_PERSONA_FREE.contains("como um irmão experiente"))
    }

    @Test
    fun personaNaoSeApresentaComoMembro() {
        assertTrue(CHAT_PERSONA_FREE.contains("ferramenta de orientação"))
        assertTrue(CHAT_PERSONA_FREE.contains("não se apresente como membro"))
    }

    @Test
    fun generationVoiceLineNaoMencionaIrmao() {
        assertFalse(GENERATION_VOICE_LINE.contains("irmão"))
        assertFalse(GENERATION_VOICE_LINE.contains("irmã"))
        assertTrue(GENERATION_VOICE_LINE.contains("orientador experiente"))
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

    @Test
    fun fontesDeOratoriaEntramNoContextoSoQuandoHaOratoria() {
        // v2.7.0 (Opção B): as fontes BE/TH saem do system estável e entram
        // no contexto (dossiê), apenas quando a seção é de oratória.
        val part = OratoryStructure.Part(
            purpose = OratoryStructure.Purpose(OratoryStructure.Source.S34, "Contentamento"),
            section = null,
            trainingSources = emptyList(),
        )
        val inferred = OratoryStructure.Inferred(
            outlineId = "o1",
            title = "Contentamento",
            introduction = part,
            development = emptyList(),
            conclusion = part,
            focus = null,
        )
        fun prompt(oratory: OratoryStructure.Inferred?) = buildDeepSeekPrompts(
            message = "melhore a transição",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null,
            blockMinutes = null,
            blockText = "x",
            oratory = oratory,
        ).system

        val comOratoria = prompt(inferred)
        assertTrue(comOratoria.contains("BE 39-42"))
        assertTrue(comOratoria.contains("A TH NÃO ensina a preparar discursos públicos"))
        assertFalse(prompt(null).contains("BE 39-42"))
    }
}
