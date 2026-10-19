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
        objective: String? = null,
        agreedApproach: String? = null,
        sectionSubPoints: List<SubPoint> = emptyList(),
        minutes: Int = 5,
        discourseType: String? = null,
        sectionTitle: String = "Título teste",
        contentHtml: String = "",
        noteTitle: String? = null,
    ): Dossier = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 0, role = role,
            title = sectionTitle, minutes = minutes, contentHtml = contentHtml,
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, objective = objective, agreedApproach = agreedApproach,
            createdAt = 0, updatedAt = 0,
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
        sectionSubPoints = sectionSubPoints,
        discourseType = discourseType,
        noteTitle = noteTitle,
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
    fun build_bodySubPoint_metaProporcionalAoMinutos() {
        // 4 min de seção → 520 palavras; 2 sub-pontos → 260 para o atual.
        val prompt = builder.build(dossier(
            role = SectionRole.BODY,
            subPoint = subPoint(),
            minutes = 4,
            sectionSubPoints = listOf(subPoint(), subPoint()),
        ))
        assertTrue(prompt.contains("cerca de 260 palavras"))
    }

    @Test
    fun build_bodySection_metaProporcionalAoMinutos() {
        // 3 min × 130 palavras/min = 390.
        val prompt = builder.build(dossier(role = SectionRole.BODY, minutes = 3))
        assertTrue(prompt.contains("cerca de 390 palavras"))
    }

    @Test
    fun build_tasks_askToFinishSentence() {
        val prompt = builder.build(dossier(role = SectionRole.INTRO, subPoint = subPoint()))
        assertTrue(prompt.contains("nunca deixe texto cortado"))
    }

    @Test
    fun buildContextBlock_bodyExcludesOtherSections() {
        val prompt = builder.buildContextBlock(
            dossier(
                role = SectionRole.BODY,
                subPoint = subPoint(),
                overview = listOf(
                    SectionMeta("s1", "Título teste", SectionRole.BODY, 0, 5),
                    SectionMeta("s2", "Outra seção", SectionRole.BODY, 1, 5),
                ),
            )
        )
        assertTrue(prompt.contains("## SEÇÃO ATUAL"))
        assertTrue(prompt.contains("Título teste"))
        assertFalse(prompt.contains("Outra seção"))
        assertFalse(prompt.contains("## TAREFA"))
    }

    @Test
    fun buildContextBlock_introIncludesOverview() {
        val prompt = builder.buildContextBlock(
            dossier(
                role = SectionRole.INTRO,
                overview = listOf(
                    SectionMeta("s1", "Introdução", SectionRole.INTRO, 0, 1),
                    SectionMeta("s2", "Corpo", SectionRole.BODY, 1, 5),
                ),
            )
        )
        assertTrue(prompt.contains("## ESTRUTURA DO DISCURSO"))
        assertTrue(prompt.contains("Corpo"))
        assertFalse(prompt.contains("## TAREFA"))
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

    // ---------- F2.3: tópico (objetivo, abordagem, linha de raciocínio) ----------

    @Test
    fun build_includesObjective() {
        val prompt = builder.build(dossier(objective = "Levar o ouvinte à ação"))
        assertTrue(prompt.contains("Objetivo: Levar o ouvinte à ação"))
    }

    @Test
    fun build_includesAgreedApproachAsHighPriority() {
        val prompt = builder.build(dossier(agreedApproach = "situação → princípio → aplicação"))
        assertTrue(prompt.contains("ABORDAGEM ACORDADA"))
        assertTrue(prompt.contains("situação → princípio → aplicação"))
    }

    @Test
    fun build_includesWholeLineOfReasoning() {
        val prompt = builder.build(
            dossier(
                sectionSubPoints = listOf(
                    subPoint().copy(id = "a", order = 0, outlineText = "primeiro ponto"),
                    subPoint().copy(id = "b", order = 1, outlineText = "segundo ponto"),
                )
            )
        )
        assertTrue(prompt.contains("Linha de raciocínio"))
        assertTrue(prompt.contains("1. primeiro ponto"))
        assertTrue(prompt.contains("2. segundo ponto"))
    }

    @Test
    fun build_overview_includesObjectiveAndSnippet() {
        val prompt = builder.buildContextBlock(
            dossier(
                role = SectionRole.INTRO,
                overview = listOf(
                    SectionMeta(
                        "s2", "Corpo", SectionRole.BODY, 1, 5,
                        objective = "Explicar a esperança",
                        snippet = "texto já desenvolvido",
                    ),
                ),
            )
        )
        assertTrue(prompt.contains("objetivo: Explicar a esperança"))
        assertTrue(prompt.contains("já desenvolvido: texto já desenvolvido"))
    }

    // ---------- P0 (alinhamento): meta proporcional ao tempo ----------

    @Test
    fun targetWordsForMinutes_ritmo130() {
        assertEquals(650, targetWordsForMinutes(5))
        assertEquals(130, targetWordsForMinutes(1))
        assertEquals(3900, targetWordsForMinutes(30))
    }

    @Test
    fun targetWordsForMinutes_semMinutos_retornaNull() {
        assertEquals(null, targetWordsForMinutes(null))
        assertEquals(null, targetWordsForMinutes(0))
        assertEquals(null, targetWordsForMinutes(-2))
    }

    @Test
    fun subPointTargetWords_fatiaEntreSubPontos() {
        // 10 min → 1300; 4 sub-pontos → 325.
        assertEquals(325, subPointTargetWords(10, 4))
        // Piso de 80 para não pedir fragmentos irrisórios.
        assertEquals(80, subPointTargetWords(1, 4))
        assertEquals(1300, subPointTargetWords(10, 0))
        assertEquals(null, subPointTargetWords(null, 3))
    }

    @Test
    fun build_semMinutos_mantemFallbackEmParagrafos() {
        val prompt = builder.build(dossier(role = SectionRole.BODY, minutes = 0))
        assertTrue(prompt.contains("2-3 parágrafos curtos"))
    }

    // ---------- UX de demonstrações (V&M): formato de diálogo ----------

    @Test
    fun demoFormat_apareceSoNoMinistryPart() {
        val demo = builder.buildContextBlock(
            dossier(discourseType = "MINISTRY_PART"),
            includeSpeakerNote = false,
        )
        assertTrue(demo, demo.contains("## FORMATO DE DEMONSTRAÇÃO"))
        assertTrue(demo, demo.contains("<p><b>Papel:</b> texto</p>"))
        assertTrue(demo, demo.contains("Não converta em listas"))

        val s34 = builder.buildContextBlock(
            dossier(discourseType = "S34_DISCOURSE"),
            includeSpeakerNote = false,
        )
        assertFalse(s34, s34.contains("FORMATO DE DEMONSTRAÇÃO"))

        val semTipo = builder.buildContextBlock(dossier(), includeSpeakerNote = false)
        assertFalse(semTipo, semTipo.contains("FORMATO DE DEMONSTRAÇÃO"))
    }

    // ---------- S-38: diretrizes da reunião para partes V&M ----------

    @Test
    fun s38_apareceSoNoMinistryPartComTipoECenario() {
        val demo = builder.buildContextBlock(
            dossier(
                discourseType = "MINISTRY_PART",
                sectionTitle = "Cultivando o interesse",
                contentHtml = "<p>5. Cultivando o interesse (4 min) DE CASA EM CASA. " +
                    "Fale sobre uma das verdades do apêndice A (lmd lição 7 ponto 4).</p>",
            ),
            includeSpeakerNote = false,
        )
        assertTrue(demo, demo.contains("## DIRETRIZES DA REUNIÃO (S-38)"))
        assertTrue(demo, demo.contains("cultivar o interesse demonstrado anteriormente"))
        assertTrue(demo, demo.contains("Cenário: De casa em casa"))
        assertTrue(demo, demo.contains("lmd lição 7 ponto 4"))

        val s34 = builder.buildContextBlock(
            dossier(discourseType = "S34_DISCOURSE"),
            includeSpeakerNote = false,
        )
        assertFalse(s34, s34.contains("DIRETRIZES DA REUNIÃO"))
    }

    @Test
    fun s38_leDescricaoNoTituloDoCardComCorpoVazio() {
        // "Colar e Pronto" v2: descrição no título do card, corpo vazio (o
        // diálogo é que vai no corpo). O tipo da parte sai do título da nota.
        val prompt = builder.buildContextBlock(
            dossier(
                discourseType = "MINISTRY_PART",
                noteTitle = "Cultivando o interesse (4 min)",
                sectionTitle = "DE CASA EM CASA. Fale sobre uma das verdades " +
                    "do apêndice A da brochura Ame as Pessoas. (lmd lição 7 ponto 4)",
                contentHtml = "",
            ),
            includeSpeakerNote = false,
        )
        assertTrue(prompt, prompt.contains("## DIRETRIZES DA REUNIÃO (S-38)"))
        assertTrue(prompt, prompt.contains("cultivar o interesse demonstrado anteriormente"))
        assertTrue(prompt, prompt.contains("Cenário: De casa em casa"))
        assertTrue(
            prompt,
            prompt.contains("Se o texto de lmd lição 7 ponto 4 estiver nas FONTES"),
        )
    }

    @Test
    fun s38_promptCompletoImprimeParaInspecao() {
        val prompt = builder.buildContextBlock(
            dossier(
                discourseType = "MINISTRY_PART",
                sectionTitle = "Iniciando conversas",
                contentHtml = "<p>4. Iniciando conversas (3 min) TESTEMUNHO INFORMAL. " +
                    "Publicador: Ola, bom dia!</p>",
            ),
            includeSpeakerNote = false,
        )
        println("=== PROMPT S-38 (fixture) ===\n$prompt")
        assertTrue(prompt.contains("iniciar uma conversa amigável"))
        assertTrue(prompt.contains("Cenário: Testemunho informal"))
    }
}
