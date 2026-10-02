package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.ResolvedBibleText
import com.bettertalker.app.domain.planning.ResolvedPublicationText
import com.bettertalker.app.domain.planning.SectionMeta
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.5d.1: testes do [DefaultDossierPromptBuilder] (puro, sem LLM/Room).
 */
class DossierPromptBuilderTest {

    private fun dossier(
        role: SectionRole = SectionRole.BODY,
        subPoint: SubPoint? = null,
        bibleTexts: List<ResolvedBibleText> = emptyList(),
        publicationTexts: List<ResolvedPublicationText> = emptyList(),
        methodPrinciples: List<String> = emptyList(),
        unresolvedRefs: List<String> = emptyList(),
        transitionContext: String? = null,
        overview: List<SectionMeta> = emptyList(),
    ): Dossier = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 0, role = role,
            title = "Título teste", minutes = 5, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 0, updatedAt = 0,
        ),
        currentSubPoint = subPoint,
        selectedText = null,
        fullContentHtml = "",
        overview = overview,
        bibleTexts = bibleTexts,
        publicationTexts = publicationTexts,
        methodPrinciples = methodPrinciples,
        unresolvedRefs = unresolvedRefs,
        transitionContext = transitionContext,
    )

    private val builder = DefaultDossierPromptBuilder()

    private fun subPoint() = SubPoint(
        id = "sp1", sectionId = "s1", order = 0, outlineText = "ponto",
        bibleRefs = emptyList(), publicationRefs = emptyList(), instruction = null,
        developedHtml = "", createdAt = 0, updatedAt = 0,
    )

    @Test
    fun build_body_containsBodyTaskInstruction() {
        val prompt = builder.build(dossier(role = SectionRole.BODY, subPoint = subPoint()))
        assertTrue(prompt.contains("Desenvolva o sub-ponto"))
    }

    @Test
    fun build_bodySubPoint_targets100to200Words() {
        val prompt = builder.build(dossier(role = SectionRole.BODY, subPoint = subPoint()))
        assertTrue(prompt.contains("100 a 200"))
    }

    @Test
    fun build_bodySection_targets150to250Words() {
        val prompt = builder.build(dossier(role = SectionRole.BODY))
        assertTrue(prompt.contains("150 a 250"))
    }

    @Test
    fun build_tasks_askToFinishSentence() {
        val prompt = builder.build(dossier(role = SectionRole.INTRO, subPoint = subPoint()))
        assertTrue(prompt.contains("nunca deixe texto cortado"))
    }

    @Test
    fun build_intro_containsIntroTaskInstruction() {
        val prompt = builder.build(dossier(role = SectionRole.INTRO))
        assertTrue(prompt.contains("Escreva uma abertura"))
    }

    @Test
    fun build_conclusion_containsConclusionTaskInstruction() {
        val prompt = builder.build(dossier(role = SectionRole.CONCLUSION))
        assertTrue(prompt.contains("Escreva um fechamento"))
    }

    @Test
    fun build_includesBibleTextsLiteral() {
        val prompt = builder.build(
            dossier(
                bibleTexts = listOf(
                    ResolvedBibleText("Gên 3:6", "Texto literal", ReferenceStatus.RESOLVED)
                )
            )
        )
        assertTrue(prompt.contains("Gên 3:6"))
        assertTrue(prompt.contains("Texto literal"))
    }

    @Test
    fun build_includesPublicationTextsLiteral() {
        val prompt = builder.build(
            dossier(
                publicationTexts = listOf(
                    ResolvedPublicationText(
                        PublicationRef("w21.08", 18, 13), "Texto pub", ReferenceStatus.RESOLVED
                    )
                )
            )
        )
        assertTrue(prompt.contains("w21.08 p. 18 §13"))
        assertTrue(prompt.contains("Texto pub"))
    }

    @Test
    fun build_includesMethodPrinciples() {
        val prompt = builder.build(dossier(methodPrinciples = listOf("Use perguntas retóricas")))
        assertTrue(prompt.contains("COMO APRESENTAR"))
        assertTrue(prompt.contains("Use perguntas retóricas"))
    }

    @Test
    fun build_unresolvedRefs_appearInDontCiteSection() {
        val prompt = builder.build(dossier(unresolvedRefs = listOf("Gên 99:99")))
        assertTrue(prompt.contains("NÃO CITE"))
        assertTrue(prompt.contains("Gên 99:99"))
    }

    @Test
    fun build_partialRef_appearsWithApproxMarker() {
        val prompt = builder.build(
            dossier(
                bibleTexts = listOf(
                    ResolvedBibleText("Gên 3:6", "Texto aprox", ReferenceStatus.PARTIAL)
                )
            )
        )
        assertTrue(prompt.contains("(aprox.)"))
    }

    @Test
    fun build_transitionContext_onlyInIntroConclusion() {
        val bodyPrompt = builder.build(
            dossier(role = SectionRole.BODY, transitionContext = "Resumo anterior")
        )
        assertFalse(bodyPrompt.contains("TRANSIÇÃO SUGERIDA"))

        val introPrompt = builder.build(
            dossier(role = SectionRole.INTRO, transitionContext = "Resumo anterior")
        )
        assertTrue(introPrompt.contains("TRANSIÇÃO SUGERIDA"))
        assertTrue(introPrompt.contains("Resumo anterior"))

        val conclusionPrompt = builder.build(
            dossier(role = SectionRole.CONCLUSION, transitionContext = "Resumo anterior")
        )
        assertTrue(conclusionPrompt.contains("TRANSIÇÃO SUGERIDA"))
    }

    @Test
    fun build_emptyDossier_degradesGracefully() {
        val prompt = builder.build(dossier())
        assertTrue(prompt.contains("## SEÇÃO ATUAL"))
        assertTrue(prompt.contains("## TAREFA"))
        assertFalse(prompt.contains("## TEXTOS BÍBLICOS"))
        assertFalse(prompt.contains("## TRECHOS DE PUBLICAÇÕES"))
        assertFalse(prompt.contains("## COMO APRESENTAR"))
        assertFalse(prompt.contains("NÃO CITE"))
        assertFalse(prompt.contains("TRANSIÇÃO SUGERIDA"))
    }

    @Test
    fun build_longRefText_truncated() {
        val long = "a".repeat(1500)
        val prompt = builder.build(
            dossier(
                bibleTexts = listOf(
                    ResolvedBibleText("Gên 3:6", long, ReferenceStatus.RESOLVED)
                )
            )
        )
        assertTrue(prompt.contains(" […]"))
        // o texto não entra inteiro
        assertFalse(prompt.contains(long))
    }

    @Test
    fun build_includesJsonFormatInstruction() {
        val prompt = builder.build(dossier())
        assertTrue(prompt.contains("usedSources"))
        assertTrue(prompt.contains("JSON"))
    }
}
