package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 3.5e.3: testes dos helpers puros da sheet de draft
 * ([alvoLabel], [stripHtml]).
 */
class DraftFormattingTest {

    private fun section(id: String, order: Int, role: SectionRole = SectionRole.BODY) =
        SpeechSection(
            id = id, noteId = "n1", order = order, role = role, title = "T$id",
            minutes = 5, contentHtml = "", bibleRefs = emptyList(),
            publicationRefs = emptyList(), methodPrinciple = null,
            createdAt = 0, updatedAt = 0,
        )

    private fun subPoint(id: String, sectionId: String, order: Int) = SubPoint(
        id = id, sectionId = sectionId, order = order, outlineText = "o",
        bibleRefs = emptyList(), publicationRefs = emptyList(), instruction = null,
        developedHtml = "", createdAt = 0, updatedAt = 0,
    )

    private fun ui(section: SpeechSection, subPoints: List<SubPoint> = emptyList()) =
        SectionUiState(section = section, subPoints = subPoints)

    @Test
    fun alvoLabel_subPoint_returnsSectionAndSubIndex() {
        val sections = listOf(
            ui(section("i", 0, SectionRole.INTRO)),
            ui(section("b", 1), listOf(subPoint("sp1", "b", 0), subPoint("sp2", "b", 1))),
        )
        val label = alvoLabel(DraftTarget.SubPoint("b", "sp2"), sections)
        assertEquals("Seção 2 · Sub-ponto 2", label)
    }

    @Test
    fun alvoLabel_section_returnsSectionIndex() {
        val sections = listOf(
            ui(section("i", 0, SectionRole.INTRO)),
            ui(section("b", 1)),
            ui(section("c", 2, SectionRole.CONCLUSION)),
        )
        assertEquals("Seção 1", alvoLabel(DraftTarget.Section("i"), sections))
        assertEquals("Seção 3", alvoLabel(DraftTarget.Section("c"), sections))
    }

    @Test
    fun stripHtml_removesTags_keepsText() {
        assertEquals(
            "Parágrafo um. Parágrafo dois.",
            stripHtml("<p>Parágrafo um.</p><p>Parágrafo dois.</p>"),
        )
        assertEquals(
            "texto com negrito aqui",
            stripHtml("<p>texto com <strong>negrito</strong> aqui</p>"),
        )
        assertEquals("", stripHtml("<p></p>"))
    }
}
