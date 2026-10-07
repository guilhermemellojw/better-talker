package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.domain.speech.DiscourseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BuildOutlineConversionTest {

    private fun sections() = listOf(
        OutlineSection("A", 4, 0, "Corpo A."),
        OutlineSection("B", 3, 1, "Corpo B."),
    )

    @Test
    fun build_validDraft_producesConversion() {
        val conv = buildOutlineConversion("Tema", 15, sections(), "", "n1")
        assertEquals("Tema", conv.noteTitle)
        assertEquals(15, conv.totalMinutes)
        assertEquals(2, conv.bodies.size)
        // T5: INTRO/CONCLUSION vazias emolduram os bodies.
        assertEquals("Introdução", conv.intro?.title)
        assertEquals("Conclusão", conv.conclusion?.title)
        assertEquals(listOf(0, 1, 2, 3), listOf(conv.intro?.order) + conv.bodies.map { it.section.order } + listOf(conv.conclusion?.order))
    }

    @Test
    fun build_titleIsPassedThrough() {
        val conv = buildOutlineConversion("Meu tema", 10, sections(), "", "n1")
        assertEquals("Meu tema", conv.noteTitle)
    }

    @Test
    fun build_preambleGoesToSpeakerNotes() {
        val conv = buildOutlineConversion("T", 10, sections(), "NOTA: teste", "n1")
        assertTrue(conv.speakerNotes.contains("NOTA: teste"))
        // T5: a NOTA não vaza para a intro (card vazio).
        assertTrue(conv.intro?.contentHtml.isNullOrEmpty())
    }

    @Test
    fun build_nullTotalMinutes_fallsBackToSumOfSections() {
        val conv = buildOutlineConversion("T", null, sections(), "", "n1")
        assertEquals(7, conv.totalMinutes)
    }

    @Test
    fun build_nullMinutesPerSection_usesFallback5() {
        val conv = buildOutlineConversion(
            "T", 10,
            listOf(OutlineSection("A", null, 0, "")),
            "", "n1",
        )
        assertEquals(5, conv.bodies.single().section.minutes)
    }

    @Test
    fun build_s34_synthesizesEmptyIntroConclusion() {
        // T5: cards vazios de 1 min emolduram os bodies.
        val conv = buildOutlineConversion("T", 10, sections(), "", "n1")
        assertEquals("Introdução", conv.intro?.title)
        assertEquals(1, conv.intro?.minutes)
        assertEquals("Conclusão", conv.conclusion?.title)
        assertEquals(1, conv.conclusion?.minutes)
        assertEquals(2, conv.bodies.size)
    }

    @Test
    fun build_treasures_producesSingleBody() {
        val conv = buildOutlineConversion(
            "T", 10,
            listOf(OutlineSection("A", 10, 0, "Ideia.")),
            "", "n1",
            discourseType = DiscourseType.TREASURES_TALK,
        )
        assertEquals(null, conv.intro)
        assertEquals(null, conv.conclusion)
        assertEquals(1, conv.bodies.size)
        assertEquals(DiscourseType.TREASURES_TALK, conv.discourseType)
    }

    @Test
    fun build_treasures_withMultipleSections_throws() {
        try {
            buildOutlineConversion("T", 10, sections(), "", "n1", DiscourseType.TREASURES_TALK)
            fail("Esperava IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").contains("exatamente 1 section"))
        }
    }

    @Test
    fun build_avulso_passesDiscourseTypeToConverter() {
        val conv = buildOutlineConversion(
            "T", 5,
            listOf(OutlineSection("A", 5, 0, "x")),
            "", "n1",
            discourseType = DiscourseType.AVULSO,
        )
        assertEquals(null, conv.intro)
        assertEquals(1, conv.bodies.size)
    }

    @Test
    fun build_defaultIsS34() {
        val conv = buildOutlineConversion("T", 10, sections(), "", "n1")
        assertEquals(DiscourseType.S34_DISCOURSE, conv.discourseType)
    }
}
