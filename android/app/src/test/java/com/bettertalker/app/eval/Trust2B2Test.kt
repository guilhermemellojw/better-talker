package com.bettertalker.app.eval

import com.bettertalker.app.data.copilot.ClaimType
import com.bettertalker.app.data.copilot.CitationFidelityState
import com.bettertalker.app.data.copilot.DocumentKind
import com.bettertalker.app.data.copilot.EvidenceCompleteness
import com.bettertalker.app.data.copilot.SemanticState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Trust2B2Test {

    private fun metadata(
        premiseId: String = "premise-1",
        kind: DocumentKind = DocumentKind.BIBLE,
        edition: String? = "w25.07",
        availability: EvidenceAvailability = EvidenceAvailability.VERIFIED_LOCAL,
        recovery: RecoveryStatus = RecoveryStatus.COMPLETE,
        completeness: EvidenceCompleteness = EvidenceCompleteness.COMPLETE,
        truncated: Boolean = false,
        exposed: Boolean = true,
        truthSource: TruthSourceType = TruthSourceType.FACTUAL_DOCUMENT,
    ): Trust2B2EvidenceMetadata = Trust2B2EvidenceMetadata(
        documentId = "doc-$premiseId",
        documentKind = kind,
        publicationReference = "ref-fixture",
        edition = edition,
        documentUnit = "v. 1",
        premiseId = premiseId,
        recoveryStatus = recovery,
        completeness = completeness,
        truncated = truncated,
        exposedToModel = exposed,
        availability = availability,
        truthSource = truthSource,
    )

    private fun case(
        id: String = "T-01",
        type: ClaimType? = ClaimType.FACTUAL_BIBLICAL,
        source: TruthSourceType = if (type == null) TruthSourceType.PENDING else TruthSourceType.FACTUAL_DOCUMENT,
        required: List<String> = listOf("documentId", "documentUnit", "humanAnnotation"),
        missing: List<String> = required,
        status: PreparationStatus = PreparationStatus.NEEDS_SOURCE,
        availability: EvidenceAvailability = EvidenceAvailability.NOT_AVAILABLE_IN_REPO,
        eligible: Boolean = false,
        truncated: Boolean = false,
        exposed: Boolean = false,
        expectedEdition: String? = null,
        evidence: List<Trust2B2EvidenceMetadata> = emptyList(),
        pilot: Boolean = false,
    ): Trust2B2Case = Trust2B2Case(
        caseId = id,
        route = EvalRoute.CHAT,
        summary = "Caso experimental $id",
        classificationStatus = if (type == null) ClassificationStatus.PENDING else ClassificationStatus.CLASSIFIED,
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
        expectedEdition = expectedEdition,
        evidenceMetadata = evidence,
        falsePositiveRisk = "FP",
        falseNegativeRisk = "FN",
        isPilot = pilot,
    )

    private fun eligibleCase(id: String = "T-01"): Trust2B2Case = case(
        id = id,
        missing = emptyList(),
        status = PreparationStatus.VERIFIED,
        availability = EvidenceAvailability.VERIFIED_LOCAL,
        eligible = true,
        exposed = true,
        expectedEdition = "w25.07",
        evidence = listOf(metadata()),
    )

    private fun human(
        caseId: String,
        annotator: String,
        state: SemanticState,
        premises: List<String> = emptyList(),
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
        assertEquals(24, cases.count { it.classificationStatus == ClassificationStatus.CLASSIFIED })
        assertEquals(24, cases.count { it.classificationStatus == ClassificationStatus.PENDING })
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
        assertThrows(IllegalArgumentException::class.java) {
            eligibleCase().copy(
                evidenceTruncated = true,
                evidenceMetadata = listOf(metadata(truncated = true)),
            )
        }
    }

    @Test
    fun evidenciaNaoExpostaNaoSustenta() {
        assertThrows(IllegalArgumentException::class.java) {
            eligibleCase().copy(
                evidenceExposedToModel = false,
                evidenceMetadata = listOf(metadata(exposed = false)),
            )
        }
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
        val c = eligibleCase()
        val annotations = listOf(
            human("T-01", "A1", SemanticState.SUPPORTED, listOf("premise-1")),
            human("T-01", "A2", SemanticState.SUPPORTED, listOf("premise-1")),
        )
        assertTrue(Trust2B2Validator.validateAnnotations(listOf(c), annotations).isEmpty())
    }

    @Test
    fun divergenciaEntreAnotadoresEhRegistrada() {
        val c = eligibleCase()
        val annotations = listOf(
            human("T-01", "A1", SemanticState.SUPPORTED, listOf("premise-1")),
            human("T-01", "A2", SemanticState.INSUFFICIENT),
        )
        val report = Trust2B2Validator.report(listOf(c), annotations)
        assertEquals(1, report.divergences)
        assertNull(report.kappa)
    }

    @Test
    fun kappaIndisponivelSemDadosSuficientes() {
        assertNull(Trust2B2Validator.agreement(emptyList()).kappa)
        val one = listOf(human("T-01", "A1", SemanticState.SUPPORTED, listOf("p")))
        assertNull(Trust2B2Validator.agreement(one).kappa)
    }

    @Test
    fun kappaExemploObrigatorioZero() {
        val annotations = listOf(
            human("T-01", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-01", "B", SemanticState.SUPPORTED, listOf("p")),
            human("T-02", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-02", "B", SemanticState.SUPPORTED, listOf("p")),
            human("T-03", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-03", "B", SemanticState.INSUFFICIENT),
            human("T-04", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-04", "B", SemanticState.INSUFFICIENT),
        )
        val result = Trust2B2Validator.agreement(annotations)
        assertEquals(0.0, result.kappa!!, 0.0001)
        assertEquals("A|B", result.reviewerPair)
    }

    @Test
    fun kappaConcordanciaPerfeitaComCategoriasVariadas() {
        val annotations = listOf(
            human("T-01", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-01", "B", SemanticState.SUPPORTED, listOf("p")),
            human("T-02", "A", SemanticState.INSUFFICIENT),
            human("T-02", "B", SemanticState.INSUFFICIENT),
            human("T-03", "A", SemanticState.UNVERIFIABLE),
            human("T-03", "B", SemanticState.UNVERIFIABLE),
            human("T-04", "A", SemanticState.CONTRADICTED, listOf("p")),
            human("T-04", "B", SemanticState.CONTRADICTED, listOf("p")),
        )
        assertEquals(1.0, Trust2B2Validator.agreement(annotations).kappa!!, 0.0001)
    }

    @Test
    fun kappaDiscordanciaCompleta() {
        val annotations = listOf(
            human("T-01", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-01", "B", SemanticState.INSUFFICIENT),
            human("T-02", "A", SemanticState.INSUFFICIENT),
            human("T-02", "B", SemanticState.SUPPORTED, listOf("p")),
        )
        assertEquals(-1.0, Trust2B2Validator.agreement(annotations).kappa!!, 0.0001)
    }

    @Test
    fun paresDeRevisoresNaoSaoMisturados() {
        val annotations = listOf(
            human("T-01", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-01", "B", SemanticState.SUPPORTED, listOf("p")),
            human("T-02", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-02", "B", SemanticState.INSUFFICIENT),
            human("T-03", "C", SemanticState.SUPPORTED, listOf("p")),
            human("T-03", "D", SemanticState.SUPPORTED, listOf("p")),
        )
        val result = Trust2B2Validator.agreement(annotations)
        assertEquals("A|B", result.reviewerPair)
        assertEquals(2, result.annotatedCases)
        assertTrue(result.reason.contains("adicionais"))
    }

    @Test
    fun kappaSemVariacaoEhIndefinido() {
        val annotations = listOf(
            human("T-01", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-01", "B", SemanticState.SUPPORTED, listOf("p")),
            human("T-02", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-02", "B", SemanticState.SUPPORTED, listOf("p")),
        )
        assertNull(Trust2B2Validator.agreement(annotations).kappa)
    }

    @Test
    fun anotacaoDuplicadaNaoCalculaKappa() {
        val annotations = listOf(
            human("T-01", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-01", "A", SemanticState.SUPPORTED, listOf("p")),
            human("T-01", "B", SemanticState.SUPPORTED, listOf("p")),
        )
        assertNull(Trust2B2Validator.agreement(annotations).kappa)
    }

    @Test
    fun dadosSinteticosNaoEntramNoKappa() {
        val annotations = listOf(
            human("T-01", "A", SemanticState.SUPPORTED, listOf("p"), AnnotationSource.SYNTHETIC),
            human("T-02", "B", SemanticState.SUPPORTED, listOf("p"), AnnotationSource.SCRIPT),
        )
        assertNull(Trust2B2Validator.agreement(annotations).kappa)
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
        assertEquals(24, report.pendingClassification)
        assertTrue(report.validationErrors.isEmpty())
    }

    @Test
    fun relatorioDinamicoRefleteDadosReais() {
        val c = eligibleCase()
        val annotations = listOf(
            human("T-01", "A1", SemanticState.SUPPORTED, listOf("premise-1")),
            human("T-01", "A2", SemanticState.SUPPORTED, listOf("premise-1")),
        )
        val report = Trust2B2Validator.report(listOf(c), annotations)
        assertEquals(1, report.verifiedCases)
        assertEquals(1, report.humanAnnotatedCases)
        assertTrue(report.limitations.any { it.contains("1 caso(s) com fonte real") })
        assertTrue(report.limitations.any { it.contains("1 caso(s) com anotação humana") })
        assertFalse(report.limitations.any { it.contains("Nenhum caso com fonte real") })
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
    fun pilotoCobreObjetivosHistoricosCorretos() {
        val goals = Trust2B2Inventory.pilotGoals()
        assertEquals(11, goals.size)
        assertTrue(goals.any { it.goal.contains("sem marcador") && it.candidateCaseIds == listOf("G02") })
        assertTrue(goals.any { it.goal.contains("falta de acesso") && it.candidateCaseIds == listOf("G03") })
        assertTrue(goals.any { it.goal.contains("fora do intervalo") && it.candidateCaseIds == listOf("G04") })
        assertTrue(goals.any { it.goal.contains("prosa normal") && it.candidateCaseIds == listOf("C08") })
        assertTrue(goals.any { it.goal.contains("Número não autorizado") && it.candidateCaseIds == listOf("C06") })
        assertFalse(goals.any { it.goal == "Paráfrase correta" })
    }

    @Test
    fun propostasFuturasNaoReutilizamIds() {
        val proposals = Trust2B2Inventory.futureEvaluationProposals()
        assertTrue(proposals.any { it.contains("Paráfrase correta") })
        assertTrue(proposals.any { it.contains("Edição incorreta") })
        assertTrue(proposals.all { !it.contains("C0") && !it.contains("G0") })
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
    fun premiseIdNaoCadastradoEhRejeitado() {
        val c = case()
        val annotation = human("T-01", "A1", SemanticState.INSUFFICIENT, listOf("missing"))
        assertTrue(Trust2B2Validator.validateAnnotations(listOf(c), listOf(annotation)).any {
            it.contains("premiseId não cadastrado")
        })
    }

    @Test
    fun fonteLocalNaoVerificadaNaoAutorizaSuporte() {
        assertThrows(IllegalArgumentException::class.java) {
            case(
                missing = emptyList(),
                status = PreparationStatus.READY_FOR_REVIEW,
                availability = EvidenceAvailability.LOCAL_SOURCE_AVAILABLE,
                eligible = true,
                exposed = true,
                expectedEdition = "w25.07",
                evidence = listOf(metadata(availability = EvidenceAvailability.LOCAL_SOURCE_AVAILABLE)),
            )
        }
    }

    @Test
    fun edicaoDivergenteNaoAutorizaSuporte() {
        assertThrows(IllegalArgumentException::class.java) {
            eligibleCase().copy(
                expectedEdition = "w24.01",
                evidenceMetadata = listOf(metadata(edition = "w25.07")),
            )
        }
    }

    @Test
    fun metadadosDeTreinamentoNaoSaoDocumentoFactual() {
        assertThrows(IllegalArgumentException::class.java) {
            eligibleCase().copy(
                evidenceMetadata = listOf(
                    metadata(kind = DocumentKind.TRAINING, truthSource = TruthSourceType.METHOD),
                ),
            )
        }
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
