package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineDiagnosisInputTest {

    private fun section(
        id: String,
        order: Int,
        role: SectionRole,
        title: String,
        contentHtml: String,
        objective: String? = null,
    ) = SpeechSection(
        id = id,
        noteId = "note-1",
        order = order,
        role = role,
        title = title,
        minutes = 5,
        contentHtml = contentHtml,
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        methodPrinciple = null,
        objective = objective,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun subPoint(
        id: String,
        sectionId: String,
        order: Int,
        outlineText: String,
        developedHtml: String,
    ) = SubPoint(
        id = id,
        sectionId = sectionId,
        order = order,
        outlineText = outlineText,
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        instruction = null,
        developedHtml = developedHtml,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun buildConverteHtmlEOrdenaSecoesESubpontos() {
        val sections = listOf(
            section("s2", 2, SectionRole.CONCLUSION, "Conclusão", "<p>Fim <b>forte</b></p>"),
            section("s0", 0, SectionRole.INTRO, "Introdução", "<p>Olá <i>mundo</i></p>", "Objetivo"),
            section("s1", 1, SectionRole.BODY, "Ponto 1", ""),
        )
        val subs = mapOf(
            "s1" to listOf(
                subPoint("p2", "s1", 2, "<p>Segundo</p>", "<p>Texto 2</p>"),
                subPoint("p1", "s1", 1, "<p>Primeiro</p>", ""),
            ),
        )
        val counts = DiagnosisSourceCounts(
            total = 4,
            resolved = 3,
            indexing = 1,
            noText = 0,
            blocking = 1,
            needsAction = 1,
        )

        val input = OutlineDiagnosisInputBuilder.build(
            s34 = null,
            noteTitle = "  Minha nota  ",
            sections = sections,
            subPointsBySection = subs,
            sourceCounts = counts,
        )

        assertEquals("Minha nota", input.noteTitle)
        assertEquals(listOf("s0", "s1", "s2"), input.sections.map { it.id })
        assertEquals("Olá mundo", input.sections[0].bodyText)
        assertEquals("Objetivo", input.sections[0].objective)
        assertEquals(listOf("p1", "p2"), input.sections[1].subPoints.map { it.id })
        assertEquals("Primeiro", input.sections[1].subPoints[0].outlineText)
        assertEquals("", input.sections[1].subPoints[0].developedText)
        assertEquals("Texto 2", input.sections[1].subPoints[1].developedText)
        assertEquals(counts, input.sourceCounts)
    }

    @Test
    fun buildTituloEmBrancoViraNull() {
        val input = OutlineDiagnosisInputBuilder.build(
            s34 = null,
            noteTitle = "   ",
            sections = emptyList(),
            subPointsBySection = emptyMap(),
            sourceCounts = DiagnosisSourceCounts.NONE,
        )
        assertNull(input.noteTitle)
        assertTrue(input.sections.isEmpty())
    }
}
