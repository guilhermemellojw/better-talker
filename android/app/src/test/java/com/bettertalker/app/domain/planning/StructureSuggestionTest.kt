package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 3 — estrutura sugerida do "Criar esboço do zero". Puro. */
class StructureSuggestionTest {

    private fun speech(total: Int, points: Int = 3) =
        suggestStructure(total, DiscourseType.S34_DISCOURSE, points)

    @Test
    fun discurso10min3pontos_gera3_3_2() {
        val s = speech(10, 3)!!
        assertEquals(listOf(1, 3, 3, 2, 1), s.sections.map { it.minutes })
        assertEquals(10, s.totalMinutes)
        assertEquals(3, s.effectivePoints)
        assertFalse(s.adjusted)
        assertEquals(
            listOf(SectionRole.INTRO, SectionRole.BODY, SectionRole.BODY, SectionRole.BODY, SectionRole.CONCLUSION),
            s.sections.map { it.role },
        )
    }

    @Test
    fun discurso15min3pontos_gera5_4_4() {
        assertEquals(listOf(1, 5, 4, 4, 1), speech(15, 3)!!.sections.map { it.minutes })
    }

    @Test
    fun discurso30min3pontos_gera10_9_9() {
        assertEquals(listOf(1, 10, 9, 9, 1), speech(30, 3)!!.sections.map { it.minutes })
    }

    @Test
    fun discurso45min4pontos_gera11_11_11_10() {
        assertEquals(
            listOf(1, 11, 11, 11, 10, 1),
            speech(45, 4)!!.sections.map { it.minutes },
        )
    }

    @Test
    fun discurso5min3pontos_gera1_1_1() {
        assertEquals(listOf(1, 1, 1, 1, 1), speech(5, 3)!!.sections.map { it.minutes })
    }

    @Test
    fun discursoComNMaiorQueRestante_reduzN() {
        val s = speech(4, 5)!!
        assertEquals(2, s.effectivePoints)
        assertTrue(s.adjusted)
        assertEquals(listOf(1, 1, 1, 1), s.sections.map { it.minutes })
        assertEquals(4, s.sections.sumOf { it.minutes })
    }

    @Test
    fun discursoComTotalMenorQue3_invalido() {
        assertNull(speech(2))
        assertNull(speech(0))
        assertNull(speech(46))
    }

    @Test
    fun demo4min_gera1Body4min() {
        val s = suggestStructure(4, DiscourseType.FREE_DEMO)!!
        assertEquals(4, s.totalMinutes)
        assertEquals(1, s.sections.size)
        assertEquals(SectionRole.BODY, s.sections.single().role)
        assertEquals("Demonstração", s.sections.single().title)
        assertEquals(4, s.sections.single().minutes)
        assertEquals(0, s.effectivePoints)
    }

    @Test
    fun demoComTotalMenorQue2_invalido() {
        assertNull(suggestStructure(1, DiscourseType.FREE_DEMO))
        assertNull(suggestStructure(46, DiscourseType.FREE_DEMO))
    }

    @Test
    fun somaDosMinutosIgualTotal_para3a45() {
        for (total in 3..45) {
            for (points in 1..5) {
                val s = speech(total, points)
                assertTrue("total=$total points=$points", s != null)
                assertEquals("total=$total points=$points", total, s!!.sections.sumOf { it.minutes })
                assertTrue("mínimo 1 min", s.sections.all { it.minutes >= 1 })
            }
        }
    }
}
