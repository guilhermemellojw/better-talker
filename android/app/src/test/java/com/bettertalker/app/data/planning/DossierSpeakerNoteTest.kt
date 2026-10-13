package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5a — a NOTA do orador (persona) também entra no dossiê
 * (draft/mini discurso), uma vez só, rotulada.
 */
class DossierSpeakerNoteTest {

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

    @Test
    fun comNota_imprimeBlocoDeOrientacao() {
        val out = DefaultDossierPromptBuilder().buildContextBlock(
            dossier("Não conte histórias sobre os demônios."),
        )
        assertTrue(out.contains("## ORIENTAÇÃO DO ORADOR (do esboço)"))
        assertTrue(out.contains("Não conte histórias sobre os demônios."))
    }

    @Test
    fun semNota_omiteBloco() {
        assertFalse(
            DefaultDossierPromptBuilder().buildContextBlock(dossier(null))
                .contains("ORIENTAÇÃO DO ORADOR"),
        )
        assertFalse(
            DefaultDossierPromptBuilder().buildContextBlock(dossier("  "))
                .contains("ORIENTAÇÃO DO ORADOR"),
        )
    }

    // ---------- F5c: flag de dedupe no chat ----------

    @Test
    fun flagFalse_omiteBlocoMesmoComNota() {
        val out = DefaultDossierPromptBuilder().buildContextBlock(
            dossier("Não conte histórias sobre os demônios."),
            includeSpeakerNote = false,
        )
        assertFalse(out.contains("ORIENTAÇÃO DO ORADOR"))
        assertFalse(out.contains("Não conte histórias sobre os demônios."))
        // O resto do contexto continua presente.
        assertTrue(out.contains("## SEÇÃO ATUAL"))
    }

    @Test
    fun flagTrueExplicita_mantemBloco() {
        val out = DefaultDossierPromptBuilder().buildContextBlock(
            dossier("Fale com bondade e convicção."),
            includeSpeakerNote = true,
        )
        assertTrue(out.contains("## ORIENTAÇÃO DO ORADOR (do esboço)"))
        assertTrue(out.contains("Fale com bondade e convicção."))
    }
}
