package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 3 — guia de linguagem em todas as rotas de chat/geração. Puro. */
class LanguageGuideTest {

    @Test
    fun guiaPreservaTermosTJEAvisaAcademicismo() {
        assertTrue(LANGUAGE_PRINCIPLES.contains("## DIRETRIZES DE LINGUAGEM"))
        assertTrue(LANGUAGE_PRINCIPLES.contains("Jeová"))
        assertTrue(LANGUAGE_PRINCIPLES.contains("as Escrituras"))
        assertTrue(LANGUAGE_PRINCIPLES.contains("publicador"))
        assertTrue(LANGUAGE_PRINCIPLES.contains("Frases curtas"))
        // "outrossim" aparece só na lista de termos a EVITAR.
        assertFalse(LANGUAGE_PRINCIPLES.substringBefore("EVITE").contains("outrossim"))
        assertTrue(LANGUAGE_PRINCIPLES.substringAfter("EVITE").contains("outrossim"))
    }

    @Test
    fun cincoPersonasRecebemGuia() {
        val personas = mapOf(
            "CHAT_PERSONA" to CHAT_PERSONA,
            "GENERATION_VOICE_LINE" to GENERATION_VOICE_LINE,
            "SPEECH_MENTOR" to GenerationPrompts.SPEECH_MENTOR,
            "DEMO_SCRIPTWRITER" to GenerationPrompts.DEMO_SCRIPTWRITER,
            "MINIMAL_SYSTEM_PROMPT" to MINIMAL_SYSTEM_PROMPT,
        )
        personas.forEach { (name, persona) ->
            assertTrue("$name sem DIRETRIZES", persona.contains("DIRETRIZES DE LINGUAGEM"))
            assertTrue("$name sem Jeová", persona.contains("Jeová"))
        }
    }

    @Test
    fun systemDoChatEEsqueletoDeGeracaoTemGuia() {
        val chat = buildDeepSeekPrompts(
            message = "melhore a transição",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null,
            blockMinutes = null,
            blockText = "x",
        ).system
        assertTrue(chat.contains("## DIRETRIZES DE LINGUAGEM"))
        assertTrue(chat.contains("Jeová"))
        assertTrue(GenerationPrompts.systemPromptFor(
            com.bettertalker.app.domain.planning.GenerationProfile.SPEECH_MENTOR
        )!!.contains("## DIRETRIZES DE LINGUAGEM"))
        assertTrue(GenerationPrompts.systemPromptFor(
            com.bettertalker.app.domain.planning.GenerationProfile.DEMO_SCRIPTWRITER
        )!!.contains("## DIRETRIZES DE LINGUAGEM"))
    }
}
