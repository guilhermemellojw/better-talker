package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.DraftPartSpec
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DossierPromptBuilderPartTest {

    private fun dossier(minutes: Int = 45) = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
            title = "Título teste", minutes = minutes, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 0, updatedAt = 0,
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
    )

    private val builder = DefaultDossierPromptBuilder()

    @Test
    fun parte1_naoConcluiETemMetaDaParte() {
        val prompt = builder.buildPart(
            dossier(),
            DraftPartSpec(index = 1, count = 2, targetWords = 2990),
        )
        assertTrue(prompt.contains("PARTE 1 DE 2"))
        assertTrue(prompt.contains("2.990"))
        assertTrue(prompt.contains("NÃO conclua"))
        assertFalse(prompt.contains("FINAL DA PARTE ANTERIOR"))
    }

    @Test
    fun parte2_continuaDoTailEConclui() {
        val prompt = builder.buildPart(
            dossier(),
            DraftPartSpec(
                index = 2,
                count = 2,
                targetWords = 2860,
                previousTail = "<p>O fim da parte um. Próxima transição</p>",
            ),
        )
        assertTrue(prompt.contains("PARTE 2 DE 2"))
        assertTrue(prompt.contains("2.860"))
        assertTrue(prompt.contains("conclua a seção"))
        assertTrue(prompt.contains("FINAL DA PARTE ANTERIOR"))
        assertTrue(prompt.contains("O fim da parte um"))
        assertFalse(prompt.contains("<p>O fim da parte um"))
    }

    @Test
    fun buildSemParte_naoTemInstrucaoDeParte() {
        val prompt = builder.build(dossier())
        assertFalse(prompt.contains("PARTE 1 DE 2"))
        assertFalse(prompt.contains("FINAL DA PARTE ANTERIOR"))
    }
}
