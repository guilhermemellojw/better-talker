package com.bettertalker.app.eval

import com.bettertalker.app.data.copilot.AttributedUserQuote
import com.bettertalker.app.data.copilot.Claim
import com.bettertalker.app.data.copilot.ClaimAssessment
import com.bettertalker.app.data.copilot.ClaimType
import com.bettertalker.app.data.copilot.ClaimVerdict
import com.bettertalker.app.data.copilot.CitationFidelity
import com.bettertalker.app.data.copilot.CitationFidelityState
import com.bettertalker.app.data.copilot.CitationOrigin
import com.bettertalker.app.data.copilot.EvidenceUnit
import com.bettertalker.app.data.copilot.SemanticState
import com.bettertalker.app.data.copilot.checkDocumentCitation
import com.bettertalker.app.data.copilot.checkS34Citation
import com.bettertalker.app.data.copilot.checkUserCitation
import com.bettertalker.app.data.copilot.normalizeLiteralText

/** Rotas cobertas pelo inventário experimental. */
enum class EvalRoute {
    CHAT,
    GLOBAL,
    SOURCE,
    CARD,
    DRAFT,
    VERIFIER,
    SOURCE_GATE,
    RAG,
    TRUST1A,
    EXPERIMENTAL,
}

/** Preparação do caso, distinta de SemanticState.NOT_EVALUATED. */
enum class DatasetStatus {
    READY,
    NEEDS_ANNOTATION,
    NOT_EVALUABLE,
}

/** Política de resposta esperada; não é resultado semântico. */
enum class EvalPolicy {
    PASS,
    WARN,
    BLOCK,
    REFUSE,
    ASK,
}

/** Caso experimental. Fixtures sintéticas são explicitamente marcadas. */
data class EvalCase(
    val caseId: String,
    val route: EvalRoute,
    val status: DatasetStatus,
    val summary: String,
    val claim: Claim? = null,
    val evidence: List<EvidenceUnit> = emptyList(),
    val documentCitation: String? = null,
    val userCitation: String? = null,
    val userQuote: AttributedUserQuote? = null,
    val s34Citation: String? = null,
    val s34Text: String? = null,
    val expectedState: SemanticState? = null,
    val expectedDocumentFidelity: CitationFidelityState? = null,
    val expectedPolicy: EvalPolicy? = null,
    val expectedEdition: String? = null,
    val annotationSource: String? = null,
    val syntheticFixture: Boolean = true,
    val notes: String? = null,
)

/** Resultado do baseline determinístico. */
data class BaselineOutcome(
    val caseId: String,
    val state: SemanticState,
    val reason: String,
    val examinedEvidenceIds: List<String>,
    val citationFidelities: List<CitationFidelity>,
    val factualEligibleCount: Int,
    val methodEligibleCount: Int,
    val assessment: ClaimAssessment,
)

data class CitationExpectation(
    val origin: CitationOrigin,
    val expected: CitationFidelityState,
)

data class EvalMetrics(
    val totalCases: Int,
    val readyCases: Int,
    val needsAnnotation: Int,
    val notEvaluable: Int,
    val executedCases: Int,
    val coverageByRoute: Map<EvalRoute, Int>,
    val coverageByClaimType: Map<ClaimType, Int>,
    val confusion: Map<SemanticState, Map<SemanticState, Int>>,
    val citationExpected: Int,
    val citationMatched: Int,
    val falsePositives: List<String>,
    val falseNegatives: List<String>,
    val runtimeMs: Long,
) {
    fun citationAccuracy(): Double =
        if (citationExpected == 0) 0.0 else citationMatched.toDouble() / citationExpected

    fun macroF1(): Double? {
        val labels = confusion.keys + confusion.values.flatMap { it.keys }
        if (labels.isEmpty()) return null
        val scores = labels.mapNotNull { label ->
            val tp = confusion[label]?.get(label) ?: 0
            val fp = confusion.entries.filter { it.key != label }.sumOf { it.value[label] ?: 0 }
            val fn = confusion[label].orEmpty().filterKeys { it != label }.values.sum()
            if (tp + fp + fn == 0) return@mapNotNull null
            val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            val recall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
            if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        }
        return if (scores.isEmpty()) null else scores.average()
    }
}

data class EvalReport(
    val outcomes: List<BaselineOutcome>,
    val metrics: EvalMetrics,
    val gaps: List<String>,
)

/** Baseline determinístico: não infere paráfrases nem contradições. */
object DeterministicBaseline {

    fun evaluate(case: EvalCase): BaselineOutcome? {
        if (case.status != DatasetStatus.READY) return null
        val claim = case.claim ?: return null
        val factual = case.evidence.filter { it.isFactualEligible() }
        val method = case.evidence.filter { it.isMethodEligible() }
        val fidelities = buildList {
            case.documentCitation?.let { citation ->
                case.evidence.firstOrNull()?.let { add(checkDocumentCitation(citation, it)) }
            }
            case.userCitation?.let { citation ->
                case.userQuote?.let { add(checkUserCitation(citation, it, "fixture:user")) }
            }
            case.s34Citation?.let { citation ->
                case.s34Text?.let { add(checkS34Citation(citation, it, "fixture:s34")) }
            }
        }

        val (state, examined, reason) = when {
            claim.type == ClaimType.USER_HYPOTHESIS ||
                claim.type == ClaimType.LITERAL_CITATION ||
                claim.type == ClaimType.STRUCTURAL_EDITORIAL ||
                claim.type == ClaimType.CREATIVE_SUGGESTION ->
                Triple(SemanticState.NOT_EVALUATED, emptyList(), "claim não factual")

            claim.type == ClaimType.ORATORY_TECHNIQUE -> when {
                method.isEmpty() -> Triple(
                    SemanticState.UNVERIFIABLE,
                    emptyList(),
                    "sem evidência de método elegível",
                )
                method.any { directTextMatch(claim.text, it.text) } -> Triple(
                    SemanticState.SUPPORTED,
                    listOf(method.first { directTextMatch(claim.text, it.text) }.documentId),
                    "correspondência textual direta em treinamento",
                )
                else -> Triple(
                    SemanticState.INSUFFICIENT,
                    method.map { it.documentId },
                    "treinamento elegível, mas sem correspondência literal",
                )
            }

            claim.type.supportEvidence == com.bettertalker.app.data.copilot.SupportEvidence.DOCUMENTARY -> when {
                case.expectedEdition != null &&
                    factual.none { it.edition == case.expectedEdition } -> Triple(
                    SemanticState.UNVERIFIABLE,
                    emptyList(),
                    "edição esperada não corresponde à evidência",
                )
                factual.isEmpty() -> Triple(
                    SemanticState.UNVERIFIABLE,
                    emptyList(),
                    "sem evidência documental elegível",
                )
                factual.any { directTextMatch(claim.text, it.text) } -> Triple(
                    SemanticState.SUPPORTED,
                    listOf(factual.first { directTextMatch(claim.text, it.text) }.documentId),
                    "correspondência textual direta",
                )
                else -> Triple(
                    SemanticState.INSUFFICIENT,
                    factual.map { it.documentId },
                    "evidência documental sem correspondência literal",
                )
            }

            else -> Triple(SemanticState.NOT_EVALUATED, emptyList(), "tipo sem regra determinística")
        }

        val verdict = ClaimVerdict(
            claimId = claim.id,
            state = state,
            examinedEvidenceIds = examined,
            reason = reason,
        )
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = verdict,
            evidence = case.evidence,
        )
        return BaselineOutcome(
            caseId = case.caseId,
            state = state,
            reason = reason,
            examinedEvidenceIds = examined,
            citationFidelities = fidelities,
            factualEligibleCount = factual.size,
            methodEligibleCount = method.size,
            assessment = assessment,
        )
    }

    private fun directTextMatch(claimText: String, evidenceText: String): Boolean {
        val claim = normalizeForMatch(claimText)
        val evidence = normalizeForMatch(evidenceText)
        return claim.isNotBlank() && evidence.contains(claim)
    }

    private fun normalizeForMatch(text: String): String = normalizeLiteralText(text)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

object EvalHarness {

    fun run(cases: List<EvalCase>, gaps: List<String> = emptyList()): EvalReport {
        val started = System.nanoTime()
        val outcomes = cases.mapNotNull { DeterministicBaseline.evaluate(it) }
        val runtimeMs = (System.nanoTime() - started) / 1_000_000

        val ready = cases.count { it.status == DatasetStatus.READY }
        val needs = cases.count { it.status == DatasetStatus.NEEDS_ANNOTATION }
        val notEvaluable = cases.count { it.status == DatasetStatus.NOT_EVALUABLE }

        val confusion = mutableMapOf<SemanticState, MutableMap<SemanticState, Int>>()
        val falsePositives = mutableListOf<String>()
        val falseNegatives = mutableListOf<String>()
        for (outcome in outcomes) {
            val case = cases.first { it.caseId == outcome.caseId }
            val expected = case.expectedState ?: continue
            confusion.getOrPut(expected) { mutableMapOf() }
                .merge(outcome.state, 1, Int::plus)
            when {
                expected != outcome.state && outcome.state == SemanticState.SUPPORTED ->
                    falsePositives += outcome.caseId
                expected != outcome.state ->
                    falseNegatives += outcome.caseId
            }
        }

        var citationExpected = 0
        var citationMatched = 0
        for (outcome in outcomes) {
            val case = cases.first { it.caseId == outcome.caseId }
            case.expectedDocumentFidelity?.let { expected ->
                citationExpected++
                if (outcome.citationFidelities.any {
                        it.origin == CitationOrigin.DOCUMENT && it.state == expected
                    }
                ) citationMatched++
            }
        }

        val metrics = EvalMetrics(
            totalCases = cases.size,
            readyCases = ready,
            needsAnnotation = needs,
            notEvaluable = notEvaluable,
            executedCases = outcomes.size,
            coverageByRoute = cases.filter { it.status == DatasetStatus.READY }
                .groupingBy { it.route }.eachCount(),
            coverageByClaimType = cases.mapNotNull { it.claim }
                .groupingBy { it.type }.eachCount(),
            confusion = confusion,
            citationExpected = citationExpected,
            citationMatched = citationMatched,
            falsePositives = falsePositives,
            falseNegatives = falseNegatives,
            runtimeMs = runtimeMs,
        )
        return EvalReport(outcomes, metrics, gaps)
    }

    fun format(report: EvalReport): String = buildString {
        appendLine("# TRUST-2B1 baseline determinístico")
        appendLine()
        appendLine("Casos inventariados: ${report.metrics.totalCases}")
        appendLine("Executáveis: ${report.metrics.executedCases}")
        appendLine("Pendentes de anotação: ${report.metrics.needsAnnotation}")
        appendLine("Não avaliáveis: ${report.metrics.notEvaluable}")
        appendLine("Cobertura por rota: ${report.metrics.coverageByRoute}")
        appendLine("Cobertura por tipo: ${report.metrics.coverageByClaimType}")
        appendLine("Fidelidade de citações: ${report.metrics.citationMatched}/${report.metrics.citationExpected}")
        appendLine("Falsos positivos: ${report.metrics.falsePositives}")
        appendLine("Falsos negativos: ${report.metrics.falseNegatives}")
        appendLine("Macro-F1: ${report.metrics.macroF1() ?: "n/d"}")
        appendLine("Runtime: ${report.metrics.runtimeMs} ms")
        appendLine()
        appendLine("| caseId | estado | motivo |")
        appendLine("|---|---|---|")
        report.outcomes.forEach {
            appendLine("| ${it.caseId} | ${it.state} | ${it.reason} |")
        }
        if (report.gaps.isNotEmpty()) {
            appendLine()
            appendLine("Lacunas: ${report.gaps.joinToString(", ")}")
        }
    }
}
