package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.2.5c: contagem de palavras do editor multi-seção (pura, sem Compose). */
class WordCountTest {

    private fun ui(
        role: String,
        contentHtml: String = "",
        subPoints: List<String> = emptyList(),
    ): SectionUiState {
        val section = SpeechSection(
            id = "s-$role", noteId = "n", order = 0,
            role = SectionRole.valueOf(role), title = "T", minutes = 1,
            contentHtml = contentHtml, bibleRefs = emptyList(),
            publicationRefs = emptyList(), methodPrinciple = null,
            createdAt = 0L, updatedAt = 0L,
        )
        val subs = subPoints.mapIndexed { i, html ->
            SubPoint(
                id = "sp$i", sectionId = section.id, order = i, outlineText = "o",
                bibleRefs = emptyList(), publicationRefs = emptyList(),
                instruction = null, developedHtml = html, createdAt = 0L, updatedAt = 0L,
            )
        }
        return SectionUiState(section = section, subPoints = subs)
    }

    @Test
    fun countWords_stripsHtmlAndCountsWords() {
        assertEquals(0, countWords(""))
        assertEquals(0, countWords("<p></p>"))
        assertEquals(0, countWords("<br/><br/>"))
        assertEquals(2, countWords("<p>Olá mundo</p>"))
        assertEquals(3, countWords("<b>Vida</b> eterna <i>agora</i>!"))
        assertEquals(1, countWords("  palavra  "))
    }

    @Test
    fun countSectionWords_sumsBodySubPointsAndSectionHtml() {
        val sections = listOf(
            ui("INTRO", contentHtml = "<p>Bom dia irmãos</p>"),                 // 3
            ui("BODY", subPoints = listOf("<p>primeiro ponto aqui</p>", "segundo")), // 3 + 1
            ui("CONCLUSION", contentHtml = "fim"),                              // 1
        )
        assertEquals(8, countSectionWords(sections))
    }

    @Test
    fun countSectionWords_emptyIsZero() {
        assertEquals(0, countSectionWords(emptyList()))
    }
}
