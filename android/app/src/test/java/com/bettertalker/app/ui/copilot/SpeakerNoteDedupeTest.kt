package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.OutlineStructureContext
import com.bettertalker.app.data.copilot.serializeStructuralContext
import com.bettertalker.app.data.planning.DefaultDossierPromptBuilder
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5c — a ORIENTAÇÃO DO ORADOR não aparece duas vezes no prompt do chat:
 * quando o bloco estrutural do S-34 já a carrega, o dossiê a omite.
 */
class SpeakerNoteDedupeTest {

    private fun dossier(note: String?) = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
            title = "Fé que age", minutes = 5, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 1L, updatedAt = 1L,
        ),
        currentSubPoint = null,
        selectedText = null,
        fullContentHtml = "",
        overview = emptyList(),
        bibleTexts = emptyList(),
        publicationTexts = emptyList(),
        methodPrinciples = emptyList(),
        unresolvedRefs = emptyList(),
        transitionContext = null,
        speakerNote = note,
    )

    private fun structural(note: String?) = OutlineStructureContext(
        outlineId = "o1",
        title = "Discurso teste",
        objective = null,
        speakerNote = note,
        orderedSections = emptyList(),
        currentSection = null,
        focusState = OutlineStructureContext.FocusState.DOCUMENT,
    )

    @Test
    fun comEstruturalComNota_dossieOmiteEOrientacaoApareceUmaVezSo() {
        val ctx = structural("Não conte histórias sobre os demônios.")
        assertFalse(dossierSpeakerNoteIncluded(ctx))
        val structuralBlock = serializeStructuralContext(ctx)
        val dossierBlock = DefaultDossierPromptBuilder().buildContextBlock(
            dossier("Não conte histórias sobre os demônios."),
            includeSpeakerNote = dossierSpeakerNoteIncluded(ctx),
        )
        assertTrue(structuralBlock.contains("[S34] ORIENTAÇÃO DO ORADOR:"))
        assertFalse(dossierBlock.contains("ORIENTAÇÃO DO ORADOR"))
        // No prompt combinado (structural + dossiê), a orientação aparece 1x.
        val combined = "$structuralBlock\n\n$dossierBlock"
        assertEquals(1, combined.split("ORIENTAÇÃO DO ORADOR").size - 1)
    }

    @Test
    fun semEstrutural_ouSemNota_dossieMantemAOrientacao() {
        assertTrue(dossierSpeakerNoteIncluded(null))
        assertTrue(dossierSpeakerNoteIncluded(structural(null)))
        assertTrue(dossierSpeakerNoteIncluded(structural("   ")))
        val block = DefaultDossierPromptBuilder().buildContextBlock(
            dossier("Fale com bondade e convicção."),
            includeSpeakerNote = dossierSpeakerNoteIncluded(structural(null)),
        )
        assertTrue(block.contains("## ORIENTAÇÃO DO ORADOR (do esboço)"))
        assertTrue(block.contains("Fale com bondade e convicção."))
    }
}
