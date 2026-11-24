package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.planning.DefaultDossierPromptBuilder
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.ResolvedBibleText
import com.bettertalker.app.domain.planning.ResolvedPublicationText
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 — seleção e classificação das fontes documentais do corpus factual do
 * gate oratório (oruaryDocumentarySources): fonte tipada e recuperada entra; editor,
 * estrutura/memória do dossiê, memória canônica e contexto de prática NÃO
 * entram no corpus factual. Puro/testável.
 */
class OruaryDocumentarySourcesTest {

    private val section = SpeechSection(
        id = "sec-1", noteId = "n1", order = 0, role = SectionRole.BODY,
        title = "Ponto", minutes = 5, contentHtml = "", bibleRefs = emptyList(),
        publicationRefs = emptyList(), methodPrinciple = null,
        createdAt = 10L, updatedAt = 10L,
    )

    private fun dossier(
        bibleTexts: List<ResolvedBibleText> = emptyList(),
        publicationTexts: List<ResolvedPublicationText> = emptyList(),
        selectedText: String? = null,
        fullContentHtml: String = "",
        speakerNote: String? = null,
        methodPrinciples: List<String> = emptyList(),
    ) = Dossier(
        currentSection = section,
        currentSubPoint = null,
        selectedText = selectedText,
        fullContentHtml = fullContentHtml,
        overview = emptyList(),
        bibleTexts = bibleTexts,
        publicationTexts = publicationTexts,
        methodPrinciples = methodPrinciples,
        unresolvedRefs = emptyList(),
        transitionContext = null,
        speakerNote = speakerNote,
    )

    private fun ref(label: String, text: String?) = OratoryGeneration.RefWithText(
        type = S34RefType.BIBLE, label = label, ownerId = "sec-1", text = text
    )

    private fun specWithRefs(refs: List<OratoryGeneration.RefWithText> = emptyList()) = OratoryGeneration.Spec(
        mode = OratoryGeneration.Mode.DEVELOPMENT,
        action = OratoryGeneration.Action.INSERT,
        outlineId = "doc-1",
        outlineTitle = "DISCURSO",
        objective = null,
        orderedSections = listOf(OratoryGeneration.SectionLine("sec-1", 1, "PONTO", true)),
        current = OratoryGeneration.CurrentSection(
            sectionId = "sec-1", order = 1, title = "PONTO",
            content = "Corpo do ponto.", subsections = emptyList(),
            references = refs,
        ),
        previousSectionId = null,
        nextSectionId = null,
        training = emptyList(),
        contentSources = emptyList(),
        target = OratoryGeneration.Target.AfterSection("sec-1"),
    )

    @Test
    fun F_corpusVazioQuandoNaoHaRecuperacao() {
        // F — corpus factual vazio: "sem verificação factual", nunca
        // "fato confirmado".
        assertTrue(OratoryGeneration.oruaryDocumentarySources(dossier(), specWithRefs(), "sec-1").isEmpty())
    }

    @Test
    fun F_dossieNuloSemRefsRecuperados() {
        assertTrue(OratoryGeneration.oruaryDocumentarySources(null, specWithRefs(), "sec-1").isEmpty())
    }

    @Test
    fun F_refsSemTextoRecuperadoNaoViramCorpus() {
        assertTrue(OratoryGeneration.oruaryDocumentarySources(dossier(), specWithRefs(listOf(ref("Salpo 103:20", null))), "sec-1").isEmpty())
    }

    @Test
    fun D_dossieComRecuperacaoViraDocumentoElegivel() {
        val v = "Louvem a Jeová, todos os seus anjos, fortes e poderosos, que cumprem a sua palavra, obedecendo ao que ele diz."
        val docs = OratoryGeneration.oruaryDocumentarySources(dossier(bibleTexts = listOf(ResolvedBibleText("Salpo 103:20", v, ReferenceStatus.RESOLVED))), specWithRefs(), "sec-1")
        assertEquals(1, docs.size)
        assertEquals(v, docs.single().text)
        assertTrue(docs.single().isDocumentaryEligible())
        assertTrue(docs.single().sourceId.contains("Salpo 103:20"))
    }

    @Test
    fun D_publicacaoRecuperadaViraDocumentoElegivel() {
        val t = "Trecho recuperado da publicação w19, p. 12."
        val docs = OratoryGeneration.oruaryDocumentarySources(dossier(publicationTexts = listOf(ResolvedPublicationText(PublicationRef(symbol = "w19", page = 12), t, ReferenceStatus.RESOLVED))), specWithRefs(), "sec-1")
        assertTrue(docs.any { it.text == t && it.isDocumentaryEligible() })
    }

    @Test
    fun D_refsDoSpecTrazemTextosRecuperadosComRotulo() {
        val t = "Louvem a Jeová, todos os seus anjos, fortes e poderosos, que cumprem a sua palavra, obedecendo ao que ele diz."
        val docs = OratoryGeneration.oruaryDocumentarySources(dossier(), specWithRefs(listOf(ref("Salpo 103:20", t))), "sec-1")
        assertEquals(1, docs.size)
        assertEquals("Salpo 103:20", docs.single().sourceId)
        assertEquals(t, docs.single().text)
        assertTrue(docs.single().isDocumentaryEligible())
    }

    @Test
    fun E_passagemPartialNaoPromoveACompleta() {
        val parcial = "[Salpo 103:20] Trecho approximado, recuperação parcial."
        val docs = OratoryGeneration.oruaryDocumentarySources(dossier(bibleTexts = listOf(ResolvedBibleText("Salpo 103:20", parcial, ReferenceStatus.PARTIAL))), specWithRefs(), "sec-1")
        assertFalse(docs.any { it.text.contains("Trecho approximado") })
        assertTrue(docs.none { it.text.contains("Trecho") })
    }

    @Test
    fun B_editorEStructuraDoDossieNaoViramCorpus() {
        val editor = "João era o aluno de Jesus, que preferia a vinha da Maria."
        val d = dossier(
            selectedText = editor,
            fullContentHtml = "<p>$editor</p>",
            speakerNote = "Nota do orador: falar sobre Jeová.",
            methodPrinciples = listOf("Princípio oratória inventado X."),
            bibleTexts = listOf(ResolvedBibleText("Salpo 34", "Texto recuperado do ponto B.", ReferenceStatus.RESOLVED)),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specWithRefs(), "sec-1")
        assertTrue(corpus.isNotEmpty())
        assertFalse(corpus.any { it.text.contains("João") })
        assertFalse(corpus.any { it.text.contains("Nota do orador") })
        assertFalse(corpus.any { it.text.contains("Princípio oratória inventado X") })
    }
}
