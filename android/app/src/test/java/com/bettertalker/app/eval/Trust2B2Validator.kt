package com.bettertalker.app.eval

import com.bettertalker.app.data.copilot.ClaimType
import com.bettertalker.app.data.copilot.SemanticState

/**
 * Validador e relatório experimental TRUST-2B2.
 *
 * Não executa verificação semântica; valida preparação de dados e separa
 * anotação humana de rótulos sintéticos/automáticos.
 */
object Trust2B2Validator {

    fun validateInventory(cases: List<Trust2B2Case>): List<String> {
        val errors = mutableListOf<String>()
        val duplicates = cases.groupingBy { it.caseId }.eachCount().filterValues { it > 1 }
        duplicates.keys.forEach { errors += "caseId duplicado: $it" }

        cases.forEach { case ->
            if (case.expectedClaimType.isFactualType() &&
                case.truthSource != TruthSourceType.FACTUAL_DOCUMENT
            ) {
                errors += "${case.caseId}: claim factual exige FACTUAL_DOCUMENT"
            }
            if (case.expectedClaimType.isFactualType() &&
                case.truthSource in setOf(
                    TruthSourceType.METHOD,
                    TruthSourceType.STRUCTURE,
                    TruthSourceType.USER_CONTEXT,
                )
            ) {
                errors += "${case.caseId}: método/estrutura/usuário não é prova factual"
            }
            if (!case.missingFields.all { it in case.requiredEvidenceFields }) {
                errors += "${case.caseId}: missingFields contém campo não exigido"
            }
            if (case.preparationStatus == PreparationStatus.READY_FOR_REVIEW &&
                case.missingFields.isNotEmpty()
            ) {
                errors += "${case.caseId}: READY_FOR_REVIEW com campos ausentes"
            }
            if (case.evidenceEligibleForSupport &&
                case.evidenceAvailability !in setOf(
                    EvidenceAvailability.LOCAL_SOURCE_AVAILABLE,
                    EvidenceAvailability.VERIFIED_LOCAL,
                )
            ) {
                errors += "${case.caseId}: suporte elegível sem fonte local verificada"
            }
            if (case.evidenceEligibleForSupport && case.evidenceTruncated) {
                errors += "${case.caseId}: evidência truncada não é elegível para suporte"
            }
            if (case.evidenceEligibleForSupport && !case.evidenceExposedToModel) {
                errors += "${case.caseId}: evidência não exposta ao modelo não é elegível"
            }
        }
        return errors
    }

    fun validateAnnotations(
        cases: List<Trust2B2Case>,
        annotations: List<Trust2B2Annotation>,
    ): List<String> {
        val errors = mutableListOf<String>()
        val byCase = cases.associateBy { it.caseId }
        val seenHuman = mutableSetOf<String>()
        annotations.forEach { annotation ->
            val case = byCase[annotation.caseId]
            if (case == null) {
                errors += "${annotation.caseId}: caso inexistente"
                return@forEach
            }
            if (annotation.isHuman) {
                val key = "${annotation.caseId}|${annotation.annotatorId}"
                if (!seenHuman.add(key)) {
                    errors += "${annotation.caseId}: anotador duplicado ${annotation.annotatorId}"
                }
            }
            if (annotation.semanticState == SemanticState.SUPPORTED) {
                if (!case.evidenceEligibleForSupport ||
                    case.evidenceTruncated ||
                    !case.evidenceExposedToModel
                ) {
                    errors += "${annotation.caseId}: SUPPORTED sem evidência elegível"
                }
                if (annotation.premiseIds.isEmpty()) {
                    errors += "${annotation.caseId}: SUPPORTED sem premiseIds"
                }
                if (!case.expectedClaimType.isFactualType() &&
                    case.expectedClaimType != ClaimType.ORATORY_TECHNIQUE
                ) {
                    errors += "${annotation.caseId}: SUPPORTED em claim não factual"
                }
            }
            if (annotation.semanticState == SemanticState.CONTRADICTED &&
                annotation.premiseIds.isEmpty()
            ) {
                errors += "${annotation.caseId}: CONTRADICTED sem premiseIds"
            }
            if (annotation.source == AnnotationSource.SYNTHETIC &&
                annotation.semanticState == SemanticState.SUPPORTED &&
                case.truthSource != TruthSourceType.FACTUAL_DOCUMENT
            ) {
                errors += "${annotation.caseId}: sintético não comprova suporte documental"
            }
        }
        return errors
    }

    fun agreement(annotations: List<Trust2B2Annotation>): AgreementResult {
        val independent = annotations
            .filter { it.source == AnnotationSource.HUMAN_INDEPENDENT }
            .groupBy { it.caseId }
            .filter { (_, list) -> list.map { it.annotatorId }.distinct().size == 2 }
        if (independent.size < 2) {
            return AgreementResult(
                kappa = null,
                reason = "casos insuficientes para Cohen's κ (mínimo 2 com dois anotadores)",
                annotatedCases = independent.size,
            )
        }
        val pairs = independent.values.map { list ->
            val first = list.first()
            val second = list.first { it.annotatorId != first.annotatorId }
            first.semanticState to second.semanticState
        }
        val n = pairs.size
        val labels = pairs.flatMap { listOf(it.first, it.second) }
        val po = pairs.count { it.first == it.second }.toDouble() / n
        val pe = labels.distinct().sumOf { label ->
            val p = labels.count { it == label }.toDouble() / labels.size
            p * p
        }
        if (pe >= 1.0) {
            return AgreementResult(
                kappa = null,
                reason = "sem variação de rótulos; κ indefinido",
                annotatedCases = independent.size,
            )
        }
        return AgreementResult(
            kappa = (po - pe) / (1.0 - pe),
            reason = "calculado sobre anotações humanas independentes",
            annotatedCases = independent.size,
        )
    }

    fun report(
        cases: List<Trust2B2Case> = Trust2B2Inventory.cases(),
        annotations: List<Trust2B2Annotation> = emptyList(),
        b2b1: EvalMetrics = Trust2B1Dataset.report().metrics,
    ): Trust2B2Report {
        val humanIndependent = annotations.filter {
            it.source == AnnotationSource.HUMAN_INDEPENDENT
        }
        val byCase = humanIndependent.groupBy { it.caseId }
        val humanAnnotated = byCase.size
        val secondPending = byCase.count { (_, list) ->
            list.map { it.annotatorId }.distinct().size == 1
        }
        val divergences = byCase.count { (_, list) ->
            list.map { it.semanticState }.distinct().size > 1
        }
        val agreement = agreement(annotations)
        return Trust2B2Report(
            totalPending = cases.size,
            preparedCases = cases.count { it.preparationStatus != PreparationStatus.INVENTORIED },
            pilotCases = cases.count { it.isPilot },
            verifiedCases = cases.count { it.preparationStatus == PreparationStatus.VERIFIED },
            humanAnnotatedCases = humanAnnotated,
            secondAnnotationPending = secondPending,
            divergences = divergences,
            kappa = agreement.kappa,
            kappaReason = agreement.reason,
            coverageByRoute = cases.groupingBy { it.route }.eachCount(),
            coverageByClaimType = cases.groupingBy { it.expectedClaimType }.eachCount(),
            missingFieldCounts = cases.flatMap { it.missingFields }
                .groupingBy { it }.eachCount(),
            pilotGoals = Trust2B2Inventory.pilotGoals(),
            t2Gap = Trust2B1Dataset.gaps,
            validationErrors = validateInventory(cases) + validateAnnotations(cases, annotations),
            b2b1Metrics = b2b1,
            limitations = listOf(
                "Nenhuma fonte real foi copiada para o repositório.",
                "0 casos verificados; 0 anotações humanas reais nesta execução.",
                "Macro-F1 de 0,7895 é do baseline sintético B2B1, não desempenho real.",
                "T2-01…T2-15 continuam sem especificação verificável na main.",
                "κ não é calculado sem dois anotadores independentes e casos suficientes.",
            ),
        )
    }

    fun format(report: Trust2B2Report): String = buildString {
        appendLine("# TRUST-2B2 — preparação de avaliação real")
        appendLine()
        appendLine("## A. Baseline sintético B2B1 (preservado)")
        appendLine("- Executáveis: ${report.b2b1Metrics.executedCases}")
        appendLine("- Macro-F1 mecânico: ${report.b2b1Metrics.macroF1() ?: "n/d"}")
        appendLine("- Falsos positivos: ${report.b2b1Metrics.falsePositives}")
        appendLine("- Falsos negativos: ${report.b2b1Metrics.falseNegatives}")
        appendLine()
        appendLine("## B. Dataset real preparado")
        appendLine("- Casos pendentes inventariados: ${report.totalPending}")
        appendLine("- Casos preparados estruturalmente: ${report.preparedCases}")
        appendLine("- Casos-piloto: ${report.pilotCases}")
        appendLine("- Casos verificados com fonte real: ${report.verifiedCases}")
        appendLine("- Casos com anotação humana: ${report.humanAnnotatedCases}")
        appendLine("- Aguardando segunda anotação: ${report.secondAnnotationPending}")
        appendLine("- Divergências: ${report.divergences}")
        appendLine("- Cohen's κ: ${report.kappa ?: "n/d"} (${report.kappaReason})")
        appendLine("- Cobertura por rota: ${report.coverageByRoute}")
        appendLine("- Cobertura por tipo: ${report.coverageByClaimType}")
        appendLine("- Campos ausentes: ${report.missingFieldCounts}")
        appendLine("- Lacuna T2: ${report.t2Gap.size} IDs")
        if (report.validationErrors.isNotEmpty()) {
            appendLine("- Erros de validação: ${report.validationErrors}")
        }
        appendLine()
        appendLine("## Piloto")
        report.pilotGoals.forEach {
            appendLine("- ${it.goal}: ${it.candidateCaseIds} — ${it.status} (${it.note})")
        }
        appendLine()
        appendLine("## Limitações")
        report.limitations.forEach { appendLine("- $it") }
    }

    private fun ClaimType.isFactualType(): Boolean =
        this == ClaimType.FACTUAL_BIBLICAL ||
            this == ClaimType.FACTUAL_DOCTRINAL ||
            this == ClaimType.FACTUAL_HISTORICAL
}
