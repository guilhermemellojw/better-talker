package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SectionValidationError
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3.1 — apresentação amigável: labels, fontes agregadas e aviso específico.
 * Puro/JVM. Os enums internos NÃO mudam.
 */
class EditorPresentationTest {

    private fun section(
        id: String = "s1",
        role: SectionRole = SectionRole.BODY,
        bibleRefs: List<String> = emptyList(),
        pubRefs: List<PublicationRef> = emptyList(),
    ) = SpeechSection(
        id = id, noteId = "n", order = 0, role = role, title = "T",
        minutes = 5, contentHtml = "", bibleRefs = bibleRefs,
        publicationRefs = pubRefs, methodPrinciple = null,
        createdAt = 0, updatedAt = 0,
    )

    private fun sub(
        id: String,
        bibleRefs: List<String> = emptyList(),
        pubRefs: List<PublicationRef> = emptyList(),
    ) = SubPoint(
        id = id, sectionId = "s1", order = 0, outlineText = "p",
        bibleRefs = bibleRefs, publicationRefs = pubRefs, instruction = null,
        developedHtml = "", createdAt = 0, updatedAt = 0,
    )

    @Test
    fun friendlyNames_areNaturalAndKeepEnums() {
        assertEquals("Introdução", friendlyRoleName(SectionRole.INTRO))
        assertEquals("Tópico de desenvolvimento", friendlyRoleName(SectionRole.BODY))
        assertEquals("Conclusão", friendlyRoleName(SectionRole.CONCLUSION))
        // enum interno intacto
        assertEquals("BODY", SectionRole.BODY.name)
    }

    @Test
    fun topicSources_aggregatesSectionAndSubPoints_dedupedInOrder() {
        val s = section(
            bibleRefs = listOf("Ro 3:23"),
            pubRefs = listOf(PublicationRef("w21.08")),
        )
        val subs = listOf(
            sub("a", bibleRefs = listOf("Ro 3:23", "Jo 3:16")),
            sub("b", pubRefs = listOf(PublicationRef("w21.08"), PublicationRef("be"))),
        )
        assertEquals(listOf("Ro 3:23", "w21.08", "Jo 3:16", "be"), topicSources(s, subs))
    }

    @Test
    fun topicSources_emptyWhenNoRefs() {
        assertTrue(topicSources(section(), listOf(sub("a"))).isEmpty())
    }

    @Test
    fun structureWarning_specificMessages() {
        assertEquals(
            "Adicione pelo menos um tópico de desenvolvimento.",
            structureWarningMessage(listOf(SectionValidationError.NO_BODY)),
        )
        assertEquals(
            "A conclusão deve ser a última parte do discurso.",
            structureWarningMessage(listOf(SectionValidationError.CONCLUSION_NOT_LAST)),
        )
        assertEquals(
            "A introdução deve ser a primeira parte do discurso.",
            structureWarningMessage(listOf(SectionValidationError.INTRO_NOT_FIRST)),
        )
    }

    @Test
    fun structureWarning_nullWhenNoErrors() {
        assertNull(structureWarningMessage(emptyList()))
    }

    @Test
    fun structureWarning_prefersActionableBodyError() {
        // NO_BODY é priorizado sobre os demais.
        val msg = structureWarningMessage(
            listOf(SectionValidationError.NON_POSITIVE_MINUTES, SectionValidationError.NO_BODY)
        )
        assertEquals("Adicione pelo menos um tópico de desenvolvimento.", msg)
    }
}
