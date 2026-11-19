package com.bettertalker.app.eval

import com.bettertalker.app.data.copilot.ClaimType
import com.bettertalker.app.data.copilot.CitationFidelityState
import com.bettertalker.app.data.copilot.SemanticState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Trust2B2Test {

    private fun case(
        id: String = "T-01",
        type: ClaimType = ClaimType.FACTUAL_BIBLICAL,
        source: TruthSourceType = TruthSourceType.FACTUAL_DOCUMENT,
        required: List<String> = listOf("documentId", "documentUnit", "humanAnnotation"),
        missing: List<String> = required,
        status: PreparationStatus = PreparationStatus.NEEDS_SOURCE,
        availability: EvidenceAvailability = EvidenceAvailability.NOT_AVAILABLE_IN_REPO,
        eligible: Boolean = false,
        truncated: Boolean = false,
        exposed: Boolean = false,
        pilot: Boolean = false,
    ): Trust2B2Case = Trust2B2Case(
        caseId = id,
        route = EvalRoute.CHAT,
        summary = "Caso experimental $id",
        expectedClaimType = type,
        truthSource = source,
        requiredEvidenceFields = required,
        missingFields = missing,
        preparationStatus = status,
        evidenceAvailability = availability,
        evaluationCriterion = "Critério de teste.",
        evidenceEligibleForSupport = eligible,
        evidenceTruncated = truncated,
        evidenceExposedToModel = exposed,
        falsePositiveRisk = "FP",
        falseNegativeRisk = "FN",
        isPilot = pilot,
    )

    private fun human(
        caseId: String,
        annotator: String,
        state: SemanticState,
        premises: List<String> = listOf("premise-1"),
        source: AnnotationSource = AnnotationSource.HUMAN_INDEPENDENT,
        rationale: String = "Justificativa rastreável.",
    ): Trust2B2Annotation = Trust2B2Annotation(
        caseId = caseId,
        annotatorId = annotator,
        semanticState = state,
        citationFidelity = CitationFidelityState.NOT_EVALUATED,
        premiseIds = premises,
        rationale = rationale,
        source = source,
    )

    @Test
    fun inventarioTem48PendentesE11Piloto() {
        val cases = Trust2B2Inventory.cases()
        assertEquals(48, cases.size)
        assertEquals(11, cases.count { it.isPilot })
        assertEquals(cases.size, cases.map { it.caseId }.distinct().size)
        assertTrue(cases.none { it.preparationStatus == PreparationStatus.VERIFIED })
    }

    @Test
    fun baselineB2b1Preservado() {
        val b2b1 = Trust2B1Dataset.report()
        assertEquals(79, b2b1.metrics.totalCases)
        assertEquals(31, b2b1.metrics.executedCases)
        assertEquals(0.7895, b2b1.metrics.macroF1()!!, 0.0001)
        assertTrue("X-CONTRA-01" in b2b1.metrics.falseNegatives)
    }

    @Test
    fun idsDuplicadosSaoRejeitados() {
        val errors = Trust2B2Validator.validateInventory(
            listOf(case(id = "DUP"), case(id = "DUP"))
        )
        assertTrue(errors.any { it.contains("duplicado") })
    }

    @Test
    fun rotuloHumanoSemAnotadorFalha() {
        assertThrows(IllegalArgumentException::class.java) {
            human("T-01", "", SemanticState.INSUFFICIENT)
        }
    }

    @Test
    fun documentoAusentePermanecePendente() {
        val c = case(missing = listOf("documentId", "humanAnnotation"))
        assertEquals(PreparationStatus.NEEDS_SOURCE, c.preparationStatus)
        assertTrue("documentId" in c.missingFields)
    }

    @Test
    fun unidadeAusentePermanecePendente() {
        val c = case(missing = listOf("documentUnit", "humanAnnotation"))
        assertEquals(PreparationStatus.NEEDS_SOURCE, c.preparationStatus)
        assertTrue("documentUnit" in c.missingFields)
    }

    @Test
    fun edicaoDivergenteNaoEhReady() {
        val c = case(
            required = listOf("documentId", "documentUnit", "edition", "humanAnnotation"),
            missing = listOf("edition"),
            status = PreparationStatus.READY_FOR_REVIEW,
        )
        assertTrue(Trust2B2Validator.validateInventory(listOf(c)).any {
            it.contains("READY_FOR_REVIEW")
        })
    }

    @Test
    fun evidenciaTruncadaNaoSustenta() {
        val c = case(
            missing = emptyList(),
            status = PreparationStatus.READY_FOR_REVIEW,
            availability = EvidenceAvailability.VERIFIED_LOCAL,
            eligible = true,
            truncated = true,
            exposed = true,
        )
        assertTrue(Trust2B2Validator.validateInventory(listOf(c)).any {
            it.contains("truncada")
        })
    }

    @Test
    fun evidenciaNaoExpostaNaoSustenta() {
        val c = case(
            missing = emptyList(),
            status = PreparationStatus.READY_FOR_REVIEW,
            availability = EvidenceAvailability.VERIFIED_LOCAL,
            eligible = true,
            truncated = false,
            exposed = false,
        )
        assertTrue(Trust2B2Validator.validateInventory(listOf(c)).any {
            it.contains("não exposta")
        })
    }

    @Test
    fun supportedSemPremissaFalha() {
        assertThrows(IllegalArgumentException::class.java) {
            human("T-01", "A1", SemanticState.SUPPORTED, premises = emptyList())
        }
    }

    @Test
    fun s34ComoProvaFactualFalha() {
        val c = case(type = ClaimType.FACTUAL_DOCTRINAL, source = TruthSourceType.STRUCTURE)
        assertTrue(Trust2B2Validator.validateInventory(listOf(c)).any {
            it.contains("método/estrutura/usuário")
        })
    }

    @Test
    fun beThComoProvaFactualFalha() {
        val c = case(type = ClaimType.FACTUAL_BIBLICAL, source = TruthSourceType.METHOD)
        assertTrue(Trust2B2Validator.validateInventory(listOf(c)).any {
            it.contains("método/estrutura/usuário")
        })
    }

    @Test
    fun anotacaoHumanaExigeJustificativa() {
        assertThrows(IllegalArgumentException::class.java) {
            human("T-01", "A1", SemanticState.INSUFFICIENT, rationale = " ")
        }
    }

    @Test
    fun anotacoesIndependentesValidas() {
        val c = case(
            missing = emptyList(),
            status = PreparationStatus.VERIFIED,
            availability = EvidenceAvailability.VERIFIED_LOCAL,
            eligible = true,
            exposed = true,
        )
        val annotations = listOf(
            human("T-01", "A1", SemanticState.SUPPORTED),
            human("T-01", "A2", SemanticState.SUPPORTED),
        )
        assertTrue(Trust2B2Validator.validateAnnotations(listOf(c), annotations).isEmpty())
    }

    @Test
    fun divergenciaEntreAnotadoresEhRegistrada() {
        val c = case(
            missing = emptyList(),
            status = PreparationStatus.VERIFIED,
            availability = EvidenceAvailability.VERIFIED_LOCAL,
            eligible = true,
            exposed = true,
        )
        val annotations = listOf(
            human("T-01", "A1", SemanticState.SUPPORTED),
            human("T-01", "A2", SemanticState.INSUFFICIENT),
        )
        val report = Trust2B2Validator.report(listOf(c), annotations)
        assertEquals(1, report.divergences)
        assertNull(report.kappa)
    }

    @Test
    fun kappaIndisponivelSemDadosSuficientes() {
        assertNull(Trust2B2Validator.agreement(emptyList()).kappa)
        val one = listOf(human("T-01", "A1", SemanticState.SUPPORTED))
        assertNull(Trust2B2Validator.agreement(one).kappa)
    }

    @Test
    fun kappaCalculadoComDoisCasos() {
        val annotations = listOf(
            human("T-01", "A1", SemanticState.SUPPORTED),
            human("T-01", "A2", SemanticState.SUPPORTED),
            human("T-02", "A1", SemanticState.INSUFFICIENT),
            human("T-02", "A2", SemanticState.INSUFFICIENT),
        )
        val result = Trust2B2Validator.agreement(annotations)
        assertEquals(1.0, result.kappa!!, 0.0001)
        assertEquals(2, result.annotatedCases)
    }

    @Test
    fun sinteticoNaoContaComoHumano() {
        val c = case()
        val synthetic = human(
            "T-01", "script", SemanticState.INSUFFICIENT,
            source = AnnotationSource.SYNTHETIC,
        )
        val report = Trust2B2Validator.report(listOf(c), listOf(synthetic))
        assertEquals(0, report.humanAnnotatedCases)
    }

    @Test
    fun pendentesNaoSaoApresentadosComoVerificados() {
        val report = Trust2B2Validator.report()
        assertEquals(0, report.verifiedCases)
        assertEquals(48, report.preparedCases)
        assertTrue(report.validationErrors.isEmpty())
    }

    @Test
    fun relatorioDeterministico() {
        val report = Trust2B2Validator.report()
        assertEquals(
            Trust2B2Validator.format(report),
            Trust2B2Validator.format(report),
        )
    }

    @Test
    fun pilotoCobreObjetivosPrioritarios() {
        val goals = Trust2B2Inventory.pilotGoals()
        assertEquals(11, goals.size)
        assertEquals(11, Trust2B2Inventory.cases().count { it.isPilot })
        assertTrue(goals.any { "be/th" in it.goal })
        assertTrue(goals.any { "Hipótese" in it.goal })
    }

    @Test
    fun anotadorDuplicadoEhRejeitado() {
        val c = case()
        val annotations = listOf(
            human("T-01", "A1", SemanticState.INSUFFICIENT),
            human("T-01", "A1", SemanticState.INSUFFICIENT),
        )
        assertTrue(Trust2B2Validator.validateAnnotations(listOf(c), annotations).any {
            it.contains("anotador duplicado")
        })
    }

    @Test
    fun sinteticoERealSaoSeparados() {
        assertTrue(Trust2B1Dataset.inventory().all { it.syntheticFixture })
        val realCases = Trust2B2Inventory.cases()
        assertTrue(realCases.all { !it.evidenceEligibleForSupport })
        assertEquals(0, Trust2B2Validator.report().verifiedCases)
    }

    @Test
    fun inventarioRealNaoTemErrosDeValidacao() {
        assertTrue(Trust2B2Validator.validateInventory(Trust2B2Inventory.cases()).isEmpty())
    }

    @Test
    fun relatorioReproduzivel() {
        val text = Trust2B2Validator.format(Trust2B2Validator.report())
        println(text)
        assertTrue(text.contains("Casos pendentes inventariados: 48"))
        assertTrue(text.contains("Casos verificados com fonte real: 0"))
    }
}
