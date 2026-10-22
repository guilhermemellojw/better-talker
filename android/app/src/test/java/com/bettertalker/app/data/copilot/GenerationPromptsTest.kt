package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.planning.GenerationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase A — personas de mentoria separadas por tipo de geração. */
class GenerationPromptsTest {

    /** Corpo da persona, sem o guia de linguagem apensado. */
    private fun body(prompt: String): String =
        prompt.substringBefore("## DIRETRIZES DE LINGUAGEM")

    @Test
    fun speechMentor_orientaEstudoBiblicoENaoTemFormatoDeDialogo() {
        val p = body(GenerationPrompts.SPEECH_MENTOR)
        assertTrue(p.contains("orientador"))
        assertTrue(p.contains("estudo bíblico"))
        assertFalse(p.contains("irmão"))
        assertFalse(p.contains("exegese"))
        assertTrue(p.contains("<texto-do-card>"))
        assertTrue(p.contains("NUNCA use formato de diálogo"))
        assertFalse(p.contains("<b>Publicador:</b>"))
        assertFalse(p.contains("<b>Morador:</b>"))
    }

    @Test
    fun speech_mentor_nao_menciona_irmao() {
        assertFalse(GenerationPrompts.SPEECH_MENTOR.contains("irmão"))
        assertFalse(GenerationPrompts.SPEECH_MENTOR.contains("irmã"))
    }

    @Test
    fun speech_mentor_nao_menciona_exegese() {
        // O guia apensado lista "exegese" em EVITE (por design); a persona
        // não deve usá-la como descrição do próprio papel.
        assertFalse(body(GenerationPrompts.SPEECH_MENTOR).contains("exegese"))
    }

    @Test
    fun demo_scriptwriter_nao_menciona_irmao() {
        assertFalse(GenerationPrompts.DEMO_SCRIPTWRITER.contains("irmão"))
        assertFalse(GenerationPrompts.DEMO_SCRIPTWRITER.contains("irmã"))
    }

    @Test
    fun demo_scriptwriter_nao_menciona_superintendente() {
        // O guia apensado preserva "superintendente" como termo TJ; a persona
        // não deve usá-lo como metáfora de identidade do Copilot.
        assertFalse(body(GenerationPrompts.DEMO_SCRIPTWRITER).contains("superintendente"))
        assertTrue(body(GenerationPrompts.DEMO_SCRIPTWRITER).contains("instrutor de campo"))
    }

    @Test
    fun speech_mentor_inclui_principios_de_oratoria() {
        val p = body(GenerationPrompts.SPEECH_MENTOR)
        assertTrue(p.contains("ESTRUTURA E PROGRESSÃO"))
        assertTrue(p.contains("ENSINO E APLICAÇÃO"))
        assertTrue(p.contains("ENTREGA"))
        assertTrue(p.contains("Adapte os princípios ao contexto"))
    }

    @Test
    fun demo_scriptwriter_inclui_principios_adaptados() {
        val p = body(GenerationPrompts.DEMO_SCRIPTWRITER)
        assertTrue(p.contains("ESTRUTURA DA CONVERSA"))
        assertTrue(p.contains("ENSINO NO DIÁLOGO"))
        assertTrue(p.contains("ENTREGA NATURAL"))
        assertTrue(p.contains("Não é discurso: é conversa"))
    }

    @Test
    fun prompt_nao_ultrapassa_5000_chars() {
        // Teto de sanidade: a persona + guia de linguagem cabem no system
        // cacheável sem inflar o prompt.
        assertTrue(GenerationPrompts.SPEECH_MENTOR.length < 5000)
        assertTrue(GenerationPrompts.DEMO_SCRIPTWRITER.length < 5000)
    }

    @Test
    fun hintsUsamModoOrientador() {
        assertTrue(GenerationPrompts.SPEECH_MENTOR_CHAT_HINT.contains("MODO ORIENTADOR"))
        assertTrue(GenerationPrompts.DEMO_SCRIPTWRITER_CHAT_HINT.contains("MODO ORIENTADOR"))
    }

    @Test
    fun demoScriptwriter_temDialogoENaoTemMonologo() {
        val p = GenerationPrompts.DEMO_SCRIPTWRITER
        assertTrue(p.contains("<b>Publicador:</b>"))
        assertTrue(p.contains("<b>Morador:</b>"))
        assertTrue(p.contains("NUNCA escreva monólogos longos"))
        assertTrue(p.contains("réplicas"))
        assertFalse(p.contains("<texto-do-card>"))
    }

    @Test
    fun structurePlanner_soEstrutura() {
        val p = GenerationPrompts.STRUCTURE_PLANNER
        assertTrue(p.contains("JSON"))
        assertTrue(p.contains("Nunca escreva texto narrativo"))
    }

    @Test
    fun chatMantemOPromptHistorico() {
        assertNull(GenerationPrompts.systemPromptFor(GenerationProfile.CHAT))
        assertNotNull(GenerationPrompts.systemPromptFor(GenerationProfile.SPEECH_MENTOR))
        assertNotNull(GenerationPrompts.systemPromptFor(GenerationProfile.DEMO_SCRIPTWRITER))
        assertNotNull(GenerationPrompts.systemPromptFor(GenerationProfile.STRUCTURE_PLANNER))
    }

    @Test
    fun profileDaLoja_parte6EhMentorDemoEhRoteirista() {
        assertEquals(
            GenerationProfile.DEMO_SCRIPTWRITER,
            GenerationProfile.forDiscourseType("MINISTRY_PART"),
        )
        assertEquals(
            GenerationProfile.SPEECH_MENTOR,
            GenerationProfile.forDiscourseType("MINISTRY_TALK"),
        )
        assertEquals(
            GenerationProfile.SPEECH_MENTOR,
            GenerationProfile.forDiscourseType("S34_DISCOURSE"),
        )
        assertEquals(
            GenerationProfile.SPEECH_MENTOR,
            GenerationProfile.forDiscourseType("TREASURES_TALK"),
        )
        assertEquals(
            GenerationProfile.SPEECH_MENTOR,
            GenerationProfile.forDiscourseType(null),
        )
    }
}
