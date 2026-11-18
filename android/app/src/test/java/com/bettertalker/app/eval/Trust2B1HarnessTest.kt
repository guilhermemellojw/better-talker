package com.bettertalker.app.eval

import com.bettertalker.app.data.copilot.CitationFidelityState
import com.bettertalker.app.data.copilot.ClaimType
import com.bettertalker.app.data.copilot.SemanticState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Trust2B1HarnessTest {

    // Baseline sintético: 5 SUPPORTED, 5 INSUFFICIENT, 11 UNVERIFIABLE, 3 NOT_EVALUATED.

    private val report by lazy { Trust2B1Dataset.report() }

    @Test
    fun inventarioPreservaIdsOriginais() {
        val expected = listOf(
            "C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09", "C10",
            "G01", "G02", "G03", "G04", "G05", "G06", "G07", "G08",
            "S01", "S02", "S03", "S04", "S05", "S06",
            "K01", "K02", "K03", "K04", "K05", "K06", "K07", "K08",
            "D01", "D02", "D03", "D04", "D05", "D06",
            "V01", "V02", "V03", "V04", "V05", "V06", "V07", "V08",
            "SG01", "SG02", "SG03", "SG04", "SG05", "SG06",
            "R01", "R02", "R03", "R04",
            "T1A-01", "T1A-02", "T1A-03", "T1A-04",
            "T1A-05", "T1A-06", "T1A-07", "T1A-08",
        )
        val ids = Trust2B1Dataset.inventory().map { it.caseId }.toSet()
        expected.forEach { assertTrue("ID ausente: $it", it in ids) }
        assertEquals(64, expected.size)
    }

    @Test
    fun lacunaT2RegistradaSemInventarCenarios() {
        assertEquals(15, Trust2B1Dataset.gaps.size)
        assertEquals("T2-01", Trust2B1Dataset.gaps.first())
        assertEquals("T2-15", Trust2B1Dataset.gaps.last())
    }

    @Test
    fun metricasDePreparacaoSaoCoerentes() {
        assertEquals(72, report.metrics.totalCases)
        assertEquals(24, report.metrics.readyCases)
        assertEquals(48, report.metrics.needsAnnotation)
        assertEquals(0, report.metrics.notEvaluable)
        assertEquals(24, report.metrics.executedCases)
        assertEquals(24, report.metrics.coverageByRoute.values.sum())
    }

    @Test
    fun baselineSuportaCorrespondenciaTextualDireta() {
        val outcome = outcome("C01")
        assertEquals(SemanticState.SUPPORTED, outcome.state)
        assertTrue(outcome.examinedEvidenceIds.isNotEmpty())
        assertEquals(1, outcome.factualEligibleCount)
    }

    @Test
    fun baselineNaoInfereParafrase() {
        assertEquals(SemanticState.INSUFFICIENT, outcome("C03").state)
        assertEquals(SemanticState.INSUFFICIENT, outcome("X-PARAPHRASE-01").state)
    }

    @Test
    fun baselineCorpusVazioNaoConfirma() {
        assertEquals(SemanticState.UNVERIFIABLE, outcome("C05").state)
        assertEquals(SemanticState.UNVERIFIABLE, outcome("T1A-07").state)
    }

    @Test
    fun baselineNaoPromoveHipoteseDoUsuario() {
        assertEquals(SemanticState.NOT_EVALUATED, outcome("C04").state)
        assertEquals(SemanticState.NOT_EVALUATED, outcome("T1A-05").state)
    }

    @Test
    fun baselineNaoPromoveTreinamentoAFactual() {
        assertEquals(SemanticState.UNVERIFIABLE, outcome("T1A-06").state)
    }

    @Test
    fun fontesInelegiveisNaoSustentam() {
        assertEquals(SemanticState.UNVERIFIABLE, outcome("C09").state)
        assertEquals(SemanticState.UNVERIFIABLE, outcome("X-UNEXPOSED-01").state)
        assertEquals(SemanticState.UNVERIFIABLE, outcome("X-PARTIAL-01").state)
        assertEquals(SemanticState.UNVERIFIABLE, outcome("X-TRUNCATED-01").state)
        assertEquals(SemanticState.UNVERIFIABLE, outcome("X-NO-UNIT-01").state)
        assertEquals(SemanticState.UNVERIFIABLE, outcome("X-EDITION-01").state)
    }

    @Test
    fun evidenciaExtraNaoExaminadaNaoAlteraVeredito() {
        val outcome = outcome("X-EXTRA-EVIDENCE-01")
        assertEquals(SemanticState.SUPPORTED, outcome.state)
        assertEquals(listOf("fixture:bible:1"), outcome.examinedEvidenceIds)
        assertEquals(2, outcome.factualEligibleCount)
    }

    @Test
    fun contradicaoNaoEhDetectadaPeloBaseline() {
        val outcome = outcome("X-CONTRA-01")
        assertEquals(SemanticState.INSUFFICIENT, outcome.state)
        assertTrue("X-CONTRA-01" in report.metrics.falseNegatives)
        assertTrue(report.metrics.falsePositives.isEmpty())
    }

    @Test
    fun fidelidadeLiteralNormalizadaEhMedida() {
        val outcome = outcome("V01")
        assertEquals(SemanticState.NOT_EVALUATED, outcome.state)
        val fidelity = outcome.citationFidelities.single()
        assertEquals(CitationFidelityState.NORMALIZED, fidelity.state)
        assertFalse(fidelity.authorizesFactualClaim)
        assertEquals(1, report.metrics.citationExpected)
        assertEquals(1, report.metrics.citationMatched)
    }

    @Test
    fun baselineNuncaSuportaSemEvidenciaElegivel() {
        report.outcomes.filter { it.state == SemanticState.SUPPORTED }.forEach { outcome ->
            assertTrue(outcome.examinedEvidenceIds.isNotEmpty())
            assertTrue(outcome.factualEligibleCount + outcome.methodEligibleCount > 0)
        }
    }

    @Test
    fun claimsNaoFactuaisPermanecemNaoAvaliados() {
        val ids = setOf("C04", "T1A-05", "V01")
        report.outcomes.filter { it.caseId in ids }.forEach {
            assertEquals(SemanticState.NOT_EVALUATED, it.state)
        }
    }

    @Test
    fun nenhumCasoSeDeclaraAnotacaoHumana() {
        Trust2B1Dataset.inventory().filter { it.status == DatasetStatus.READY }.forEach {
            assertTrue(it.syntheticFixture)
            assertNotNull(it.annotationSource)
            assertTrue(it.annotationSource!!.contains("sintética", ignoreCase = true))
        }
    }

    @Test
    fun tiposDeClaimCobremFactualEContexto() {
        val types = Trust2B1Dataset.inventory().mapNotNull { it.claim?.type }.toSet()
        assertTrue(ClaimType.FACTUAL_BIBLICAL in types)
        assertTrue(ClaimType.USER_HYPOTHESIS in types)
        assertTrue(ClaimType.LITERAL_CITATION in types)
    }

    @Test
    fun relatorioReproduzivel() {
        val text = EvalHarness.format(report)
        println(text)
        assertTrue(text.contains("Casos inventariados: 72"))
        assertTrue(text.contains("Executáveis: 24"))
    }

    private fun outcome(caseId: String): BaselineOutcome =
        report.outcomes.first { it.caseId == caseId }
}
