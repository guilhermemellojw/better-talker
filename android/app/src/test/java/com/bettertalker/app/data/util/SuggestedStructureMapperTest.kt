package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.suggestStructure
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 3 — mapper puro SuggestedStructure → PastedNote. */
class SuggestedStructureMapperTest {

    @Test
    fun discurso10min_mapeiaSecoesVaziasNaOrdem() {
        val structure = suggestStructure(10, DiscourseType.S34_DISCOURSE, 3)!!
        val note = structure.toPastedNote("  A esperança é o segredo  ")

        assertEquals("A esperança é o segredo", note.title)
        assertEquals(DiscourseType.S34_DISCOURSE, note.discourseType)
        assertEquals("", note.plainText)
        assertEquals(5, note.sections.size)
        assertEquals(
            listOf(SectionRole.INTRO, SectionRole.BODY, SectionRole.BODY, SectionRole.BODY, SectionRole.CONCLUSION),
            note.sections.map { it.role },
        )
        assertEquals(
            listOf("Introdução", "Ponto 1", "Ponto 2", "Ponto 3", "Conclusão"),
            note.sections.map { it.title },
        )
        assertEquals(listOf(1, 3, 3, 2, 1), note.sections.map { it.minutes })
        assertTrue(note.sections.all { it.contentHtml.isEmpty() })
    }

    @Test
    fun demoLivre4min_mapeiaUmBody() {
        val structure = suggestStructure(4, DiscourseType.FREE_DEMO)!!
        val note = structure.toPastedNote("Demonstração de vendas")

        assertEquals(DiscourseType.FREE_DEMO, note.discourseType)
        assertEquals(1, note.sections.size)
        assertEquals(SectionRole.BODY, note.sections.single().role)
        assertEquals("Demonstração", note.sections.single().title)
        assertEquals(4, note.sections.single().minutes)
        assertEquals("", note.sections.single().contentHtml)
    }
}
