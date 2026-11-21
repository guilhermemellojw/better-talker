package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.planning.DefaultDossierPromptBuilder
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.2B0 P6 — resolução pura do contexto oratório da seção resolvida. */
class OratoryContextResolverTest {

    private fun dossier(sectionId: String, title: String = "Ponto") = Dossier(
        currentSection = SpeechSection(
            id = sectionId, noteId = "n1", order = 0, role = SectionRole.BODY,
            title = title, minutes = 5, contentHtml = "", bibleRefs = emptyList(),
            publicationRefs = emptyList(), methodPrinciple = null,
            createdAt = 10L, updatedAt = 10L,
        ),
        currentSubPoint = null, selectedText = null, fullContentHtml = "",
        overview = emptyList(), bibleTexts = emptyList(), publicationTexts = emptyList(),
        methodPrinciples = emptyList(), unresolvedRefs = emptyList(),
        transitionContext = null, sectionSubPoints = emptyList(),
    )

    private val builder = DefaultDossierPromptBuilder()

    @Test
    fun dossieCompativelUsaDossieDaSecaoResolvida() {
        val resolved = dossier("s2", "Conclusão")
        val r = OratoryContextResolver.resolve(
            sectionId = "s2", resolvedDossier = resolved,
            includeSpeakerNote = false, practiceContext = null, promptBuilder = builder,
        )
        assertTrue(r.compatible)
        assertTrue(r.contextBlock!!.contains("Conclusão"))
        assertEquals(0, r.authorizedRefs.size)
    }

    @Test
    fun dossieIncompativelNaoUsaDossieDoFoco() {
        val wrong = dossier("s1", "Desenvolvimento")
        val memory = DecisionPromptMemory.notApplicable()
        val r = OratoryContextResolver.resolve(
            sectionId = "s2", resolvedDossier = wrong,
            includeSpeakerNote = false, practiceContext = null, promptBuilder = builder,
        )
        assertFalse(r.compatible)
        org.junit.Assert.assertNull(r.contextBlock)
        assertTrue(r.authorizedRefs.isEmpty())
    }

    @Test
    fun dossieAusenteDegradaSemContexto() {
        val memory = DecisionPromptMemory.notApplicable()
        val r = OratoryContextResolver.resolve(
            sectionId = "s2", resolvedDossier = null,
            includeSpeakerNote = false, practiceContext = null, promptBuilder = builder,
        )
        assertFalse(r.compatible)
        org.junit.Assert.assertNull(r.contextBlock)
        assertTrue(r.authorizedRefs.isEmpty())
    }
}
