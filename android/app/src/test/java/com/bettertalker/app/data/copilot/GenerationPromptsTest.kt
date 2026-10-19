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

    @Test
    fun speechMentor_orientaExegeseENaoTemFormatoDeDialogo() {
        val p = GenerationPrompts.SPEECH_MENTOR
        assertTrue(p.contains("mentor"))
        assertTrue(p.contains("exegese"))
        assertTrue(p.contains("<texto-do-card>"))
        assertTrue(p.contains("NUNCA use formato de diálogo"))
        assertFalse(p.contains("<b>Publicador:</b>"))
        assertFalse(p.contains("<b>Morador:</b>"))
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
