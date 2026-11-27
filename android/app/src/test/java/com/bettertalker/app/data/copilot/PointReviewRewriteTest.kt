package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.s34.S34Reference
import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-PEDAGOGY/P1 — regressão AI-09.
 *
 * "Revisar rascunho −20%, preservar refs" precisa entregar TEXTO revisado
 * (card), não apenas análise; a redução e as referências são validadas
 * deterministicamente antes de qualquer oferta de substituição.
 */
class PointReviewRewriteTest {

    private fun section(
        bodyText: String,
        subs: List<Pair<String, String>> = emptyList(),
        minutes: Int = 4,
    ) = NoteSectionSnapshot(
        id = "section-2",
        order = 2,
        role = SectionRole.BODY,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = minutes,
        bodyText = bodyText,
        subPoints = subs.mapIndexed { index, (outline, developed) ->
            NoteSubPointSnapshot("sub-$index", index, outline, developed)
        },
    )

    private fun deterministic(section: NoteSectionSnapshot) = PointReviewReport(
        sectionId = section.id,
        pointNumber = 2,
        title = section.title,
        minutes = section.minutes,
        overall = DiagnosisLevel.ATTENTION,
        reading = "Ponto 2 · 4 min",
        strengths = listOf("O ponto segue o S-34."),
        improvements = listOf("Inclua uma aplicação prática."),
        nextStep = PointReviewNextStep.OpenEditor(
            sectionId = section.id,
            title = section.title,
            sectionNumber = 2,
        ),
        generatedAt = 1L,
    )

    private fun s34Section() = S34Section(
        id = "sec-2",
        order = 2,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = 4,
        content = "§9 Adão e Eva desobedeceram. §10 Foram expulsos.",
        references = listOf(
            S34Reference(
                type = S34RefType.BIBLE,
                rawText = "Gênesis 3:6",
                normalizedReference = "Gênesis|3|6",
                order = 1,
                sourceLine = 1,
            ),
        ),
        sourceLine = 1,
    )

    private fun context(
        section: NoteSectionSnapshot,
        s34: S34Section? = s34Section(),
    ) = PointReviewQualityContext(
        pointNumber = 2,
        title = section.title,
        minutes = section.minutes,
        noteTitle = "Nota de teste",
        s34Objective = "Mostrar por que a vida eterna foi perdida.",
        s34Section = s34,
        section = section,
        deterministic = deterministic(section),
        sourceCounts = DiagnosisSourceCounts.NONE,
        nextPointTitle = "COMO É POSSÍVEL TER VIDA ETERNA",
    )

    private fun words(n: Int): String = (1..n).joinToString(" ") { "palavra$it" }

    @Test
    fun ai09_detectaPedidoDeReescritaComReducao() {
        val ai09 = "Revise o rascunho do ponto 2 e reduza 20%, preservando as referências."
        assertTrue(isPointRewriteRequest(ai09))
        assertEquals(20, requestedReductionPercent(ai09))

        assertEquals(
            20,
            requestedReductionPercent("Revisar rascunho −20%, preservar refs"),
        )
        assertFalse(isPointRewriteRequest("Avalie o texto do ponto 2."))
        assertFalse(isPointRewriteRequest("Avalie o texto e aumente 20%."))
        assertTrue(isPointRewriteRequest("Revise o texto do ponto 2 e encurte."))
        assertNull(requestedReductionPercent("Só avalie a clareza."))
    }

    @Test
    fun ai09_promptIncluiTextoRealLimiteEPreservacaoDeRefs() {
        val original = words(100) + " João 17:17."
        val prompts = buildPointReviewRewritePrompts(
            context = context(section(original)),
            reductionPercent = 20,
            requiredRefs = listOf("João 17:17"),
        )

        assertEquals(original, prompts.originalText)
        assertEquals(102, prompts.originalWords)
        assertEquals(20, prompts.reductionPercent)
        assertEquals(82, prompts.targetWords)
        assertEquals(71, prompts.minWords)
        assertEquals(87, prompts.maxWords)
        assertTrue(prompts.user.contains(original))
        assertTrue(prompts.user.contains("20%"))
        assertTrue(prompts.user.contains("João 17:17"))
        assertTrue(prompts.user.contains("82"))
        assertTrue(prompts.system.contains("<texto-do-card>"))
        assertTrue(prompts.system.contains("Não acrescente"))
        assertTrue(prompts.system.contains("referências"))
        assertFalse(prompts.system.contains("devolva o JSON"))
    }

    @Test
    fun ai09_validaReducaoEExigePreservacaoDasReferencias() {
        val original = words(100) + " João 17:17."
        val prompts = buildPointReviewRewritePrompts(
            context = context(section(original)),
            reductionPercent = 20,
            requiredRefs = listOf("João 17:17"),
        )

        val valid = validatePointRewrite(
            prompts,
            words(78) + " João 17:17.",
        )
        assertTrue(valid.reason, valid.ok)
        assertEquals(80, valid.wordCount)

        val poucoReduzido = validatePointRewrite(
            prompts,
            words(95) + " João 17:17.",
        )
        assertFalse(poucoReduzido.ok)
        assertTrue(poucoReduzido.reason!!.contains("redução"))

        val curtoDemais = validatePointRewrite(
            prompts,
            words(50) + " João 17:17.",
        )
        assertFalse(curtoDemais.ok)
        assertTrue(curtoDemais.reason!!.contains("curto"))

        val semRef = validatePointRewrite(prompts, words(80))
        assertFalse(semRef.ok)
        assertEquals(listOf("João 17:17"), semRef.missingRefs)

        val refNova = validatePointRewrite(
            prompts,
            words(78) + " João 17:17. Mateus 24:14.",
        )
        assertFalse(refNova.ok)
        assertTrue(refNova.newRefs.any { it.contains("Mateus") })
    }

    @Test
    fun ai09_reconheceRascunhoAcimaSemConfundirComPontoEspecifico() {
        val pedido = "Revise o rascunho acima"
        assertTrue(isPointRewriteRequest(pedido))
        assertTrue(refersToPreviousDraft(pedido))
        assertFalse(refersToPreviousDraft("Revise o texto do ponto 2"))
        assertTrue(isPointRewriteRequest("Agora reduza 20% sobre o texto acima"))
    }

    @Test
    fun ai09_textoSubstituivelEoCorpoSemDuplicarSubpontos() {
        val s = section(
            bodyText = "Corpo original do ponto.",
            subs = listOf("Primeiro subponto" to "Texto desenvolvido do subponto."),
        )
        assertEquals("Corpo original do ponto.", pointReviewDraftText(s))
        val prompts = buildPointReviewRewritePrompts(context(s), reductionPercent = null)
        assertFalse(prompts.user.contains("Texto desenvolvido do subponto"))
        assertFalse(prompts.originalText.contains("Texto desenvolvido do subponto"))
        assertTrue(prompts.system.contains("subpontos"))
        assertTrue(prompts.system.contains("NÃO serão substituídos"))
    }

    @Test
    fun ai09_analiseNaoViraTextoRevisado() {
        assertNull(
            extractPointRewriteText(
                "Aqui está a análise: o texto pode ficar mais claro e menor."
            )
        )
        assertEquals(
            "Texto revisado.",
            extractPointRewriteText(
                "<texto-do-card>Texto revisado.</texto-do-card>"
            ),
        )
    }

    @Test
    fun ai09_semReducaoAindaEntregaTextoEValidaRefs() {
        val original = words(60) + " Gênesis 3:6."
        val prompts = buildPointReviewRewritePrompts(
            context = context(section(original)),
            reductionPercent = null,
            requiredRefs = listOf("Gênesis 3:6"),
        )
        assertNull(prompts.reductionPercent)
        assertTrue(prompts.system.contains("texto revisado"))

        val valid = validatePointRewrite(prompts, words(60) + " Gênesis 3:6.")
        assertTrue(valid.reason, valid.ok)
        assertNotNull(extractPointRewriteText("<texto-do-card>${valid.wordCount}</texto-do-card>"))
    }
}
