package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 3.5b.3: testes dos adapters puros ([selectionContractOf],
 * [sectionWithSubPointsOf], [buildDossierFrom]).
 */
class DossierAdaptersTest {

    private fun section(id: String = "s1") = SpeechSection(
        id = id, noteId = "n1", order = 0, role = SectionRole.BODY,
        title = "T", minutes = 5, contentHtml = "",
        bibleRefs = emptyList(), publicationRefs = emptyList(),
        methodPrinciple = null, createdAt = 0, updatedAt = 0,
    )

    private class FakeBuilder : DossierBuilder {
        var calls = 0
        var lastContext: SelectionContextContract? = null
        var lastResult: Dossier? = null

        override suspend fun build(
            context: SelectionContextContract,
            document: List<SectionWithSubPoints>,
        ): Dossier {
            calls++
            lastContext = context
            val section = document.first().section
            val d = Dossier(
                currentSection = section,
                currentSubPoint = null,
                selectedText = context.selectedText.takeIf { it.isNotBlank() },
                fullContentHtml = context.fullContentHtml,
                overview = emptyList(),
                bibleTexts = emptyList(),
                publicationTexts = emptyList(),
                methodPrinciples = emptyList(),
                unresolvedRefs = emptyList(),
                transitionContext = null,
            )
            lastResult = d
            return d
        }
    }

    @Test
    fun selectionContractOf_mapsAllFields() {
        val c = selectionContractOf("s1", "sp1", "sel", "<p>x</p>")
        assertEquals("s1", c.sectionId)
        assertEquals("sp1", c.subPointId)
        assertEquals("sel", c.selectedText)
        assertEquals("<p>x</p>", c.fullContentHtml)
    }

    @Test
    fun sectionWithSubPointsOf_mapsFields() {
        val s = section()
        val sub = SubPoint(
            id = "sp1", sectionId = "s1", order = 0, outlineText = "o",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            instruction = null, developedHtml = "",
            createdAt = 0, updatedAt = 0,
        )
        val pair = sectionWithSubPointsOf(s, listOf(sub))
        assertSame(s, pair.section)
        assertEquals(1, pair.subPoints.size)
    }

    @Test
    fun buildDossierFrom_nullSectionId_returnsNull() = runBlocking {
        val result = buildDossierFrom(
            sections = emptyList(), sectionId = null, subPointId = null,
            selectedText = "", fullContentHtml = "", builder = FakeBuilder(),
        )
        assertNull(result)
    }

    @Test
    fun buildDossierFrom_sectionNotInList_returnsNull() = runBlocking {
        val result = buildDossierFrom(
            sections = emptyList(), sectionId = "s1", subPointId = null,
            selectedText = "", fullContentHtml = "", builder = FakeBuilder(),
        )
        assertNull(result)
    }

    @Test
    fun buildDossierFrom_validInput_callsBuilder() = runBlocking {
        val fake = FakeBuilder()
        val s = section()
        val result = buildDossierFrom(
            sections = listOf(sectionWithSubPointsOf(s, emptyList())),
            sectionId = "s1", subPointId = null,
            selectedText = "x", fullContentHtml = "<p>y</p>",
            builder = fake,
        )
        assertEquals("s1", fake.lastContext?.sectionId)
        assertEquals(1, fake.calls)
        assertEquals(result, fake.lastResult)
    }

    @Test
    fun buildDossierFrom_emptySelection_usesEmptyString() = runBlocking {
        val fake = FakeBuilder()
        val s = section()
        buildDossierFrom(
            sections = listOf(sectionWithSubPointsOf(s, emptyList())),
            sectionId = "s1", subPointId = null,
            selectedText = "", fullContentHtml = "",
            builder = fake,
        )
        assertEquals("", fake.lastContext?.selectedText)
    }
}
