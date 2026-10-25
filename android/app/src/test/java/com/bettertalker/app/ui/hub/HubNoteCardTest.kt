package com.bettertalker.app.ui.hub

import com.bettertalker.app.domain.speech.DiscourseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hubs Treinar/Apresentar: badge de tipo e duração dos cards. Puro. */
class HubNoteCardTest {

    @Test
    fun badge_ministerioEDemonstracao() {
        assertEquals("Demonstração", hubBadgeLabel(DiscourseType.MINISTRY_PART.name))
        assertEquals("Demonstração", hubBadgeLabel(DiscourseType.FREE_DEMO.name))
    }

    @Test
    fun badge_demaisTiposEDiscurso() {
        assertEquals("Discurso", hubBadgeLabel(DiscourseType.S34_DISCOURSE.name))
        assertEquals("Discurso", hubBadgeLabel(DiscourseType.TREASURES_TALK.name))
        // Fase A: parte 6 "Discurso" é discurso, não diálogo.
        assertEquals("Discurso", hubBadgeLabel(DiscourseType.MINISTRY_TALK.name))
        assertEquals("Discurso", hubBadgeLabel(DiscourseType.AVULSO.name))
        assertEquals("Discurso", hubBadgeLabel(null))
    }

    @Test
    fun minutos_somaDasSecoesTemPrioridade() {
        assertEquals(12, hubMinutesOf(sectionMinutesSum = 12, outlineMinutes = 30))
    }

    @Test
    fun minutos_fallbackDoEsbocoQuandoSemSecoes() {
        assertEquals(30, hubMinutesOf(sectionMinutesSum = 0, outlineMinutes = 30))
    }

    @Test
    fun minutos_semDados_ficaNulo() {
        assertNull(hubMinutesOf(sectionMinutesSum = 0, outlineMinutes = null))
        assertNull(hubMinutesOf(sectionMinutesSum = 0, outlineMinutes = 0))
    }
}
