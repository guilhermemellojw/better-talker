package com.bettertalker.app.eval

import com.bettertalker.app.data.copilot.ClaimType
import com.bettertalker.app.data.copilot.DocumentKind
import com.bettertalker.app.data.copilot.SemanticState
import com.bettertalker.app.data.copilot.SupportEvidence

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
            if (case.classificationStatus == ClassificationStatus.PENDING) {
                if (case.expectedClaimType != null) {
                    errors += "${case.caseId}: PENDING com claim definido"
                }
                if (case.truthSource != TruthSourceType.PENDING) {
                    errors += "${case.caseId}: PENDING sem truthSource PENDING"
                }
            }
            val type = case.expectedClaimType
            if (type != null && type.isFactualType() &&
                case.truthSource != TruthSourceType.FACTUAL_DOCUMENT
            ) {
                errors += "${case.caseId}: claim factual exige FACTUAL_DOCUMENT"
            }
            if (type != null && type.isFactualType() &&
                case.truthSource in setOf(
                    TruthSourceType.METHOD,
                    TruthSourceType.STRUCTURE,
                    TruthSourceType.USER_CONTEXT,
                    TruthSourceType.PENDING,
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
            if (case.evidenceEligibleForSupport) {
                val type = case.expectedClaimType
                if (type == null) {
                    errors += "${case.caseId}: suporte elegível sem claim classificado"
                } else {
                    when (type.supportEvidence) {
                        SupportEvidence.DOCUMENTARY -> {
                            if (case.evidenceAvailability != EvidenceAvailability.VERIFIED_LOCAL) {
                                errors += "${case.caseId}: suporte elegível sem fonte local verificada"
                            }
                            if (case.evidenceMetadata.none {
                                    it.isVerifiedDocumentary(case.expectedEdition)
                                }
                            ) {
                                errors += "${case.caseId}: suporte factual sem documento verificado"
                            }
                        }
                        SupportEvidence.METHOD -> {
                            if (case.evidenceMetadata.none { it.isVerifiedMethod() }) {
                                errors += "${case.caseId}: suporte de oratória sem método verificado"
                            }
                        }
                        SupportEvidence.NONE ->
                            errors += "${case.caseId}: SUPPORTED não permitido para ${type.name}"
                    }
                    if (case.evidenceTruncated) {
                        errors += "${case.caseId}: evidência truncada não é elegível para suporte"
                    }
                    if (!case.evidenceExposedToModel) {
                        errors += "${case.caseId}: evidência não exposta ao modelo não é elegível"
                    }
                }
            }
            case.evidenceMetadata.forEach { metadata ->
                if (metadata.documentKind in setOf(DocumentKind.BIBLE, DocumentKind.PUBLICATION) &&
                    metadata.truthSource != TruthSourceType.FACTUAL_DOCUMENT
                ) {
                    errors += "${case.caseId}: documento bíblico/publicação exige FACTUAL_DOCUMENT"
                }
                if (metadata.truthSource == TruthSourceType.METHOD &&
                    metadata.documentKind != DocumentKind.TRAINING
                ) {
                    errors += "${case.caseId}: METHOD exige DocumentKind.TRAINING"
                }
                if (metadata.truthSource == TruthSourceType.STRUCTURE &&
                    metadata.documentKind != DocumentKind.S34
                ) {
                    errors += "${case.caseId}: STRUCTURE exige DocumentKind.S34"
                }
                if (metadata.truthSource == TruthSourceType.USER_CONTEXT &&
                    metadata.documentKind != DocumentKind.USER_MESSAGE
                ) {
                    errors += "${case.caseId}: USER_CONTEXT exige DocumentKind.USER_MESSAGE"
                }
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
            val metadataById = case.evidenceMetadata.associateBy { it.premiseId }
            annotation.premiseIds.forEach { premise ->
                if (premise !in metadataById) {
                    errors += "${annotation.caseId}: premiseId não cadastrado $premise"
                }
            }
            if (annotation.semanticState == SemanticState.SUPPORTED) {
                val claimType = case.expectedClaimType
                if (claimType == null) {
                    errors += "${annotation.caseId}: SUPPORTED em cenário sem classificação"
                } else {
                    when (claimType.supportEvidence) {
                        SupportEvidence.DOCUMENTARY -> {
                            if (!case.evidenceEligibleForSupport ||
                                case.evidenceTruncated ||
                                !case.evidenceExposedToModel
                            ) {
                                errors += "${annotation.caseId}: SUPPORTED factual sem evidência elegível"
                            }
                            if (annotation.premiseIds.isEmpty()) {
                                errors += "${annotation.caseId}: SUPPORTED factual sem premiseIds"
                            } else if (!annotation.premiseIds.any { id ->
                                    metadataById[id]?.isVerifiedDocumentary(case.expectedEdition) == true
                                }
                            ) {
                                errors += "${annotation.caseId}: SUPPORTED factual sem documento verificado"
                            }
                        }
                        SupportEvidence.METHOD -> {
                            if (!case.evidenceEligibleForSupport ||
                                case.evidenceTruncated ||
                                !case.evidenceExposedToModel
                            ) {
                                errors += "${annotation.caseId}: SUPPORTED de oratória sem evidência elegível"
                            }
                            if (annotation.premiseIds.isEmpty()) {
                                errors += "${annotation.caseId}: SUPPORTED de oratória sem premiseIds"
                            } else if (!annotation.premiseIds.any { id ->
                                    metadataById[id]?.isVerifiedMethod() == true
                                }
                            ) {
                                errors += "${annotation.caseId}: SUPPORTED de oratória sem método verificado"
                            }
                        }
                        SupportEvidence.NONE ->
                            errors += "${annotation.caseId}: SUPPORTED não é permitido para ${claimType.name}"
                    }
                }
            }
            if (annotation.semanticState == SemanticState.CONTRADICTED) {
                val claimType = case.expectedClaimType
                if (claimType == null || claimType.supportEvidence != SupportEvidence.DOCUMENTARY) {
                    errors += "${annotation.caseId}: CONTRADICTED só é permitido para claim factual"
                } else if (annotation.premiseIds.isEmpty()) {
                    errors += "${annotation.caseId}: CONTRADICTED sem premiseIds"
                } else if (!annotation.premiseIds.any { id ->
                        metadataById[id]?.isVerifiedDocumentary(case.expectedEdition) == true
                    }
                ) {
                    errors += "${annotation.caseId}: CONTRADICTED sem documento verificado"
                }
            }
        }
        return errors
    }

    fun agreement(annotations: List<Trust2B2Annotation>): AgreementResult {
        val independent = annotations.filter {
            it.source == AnnotationSource.HUMAN_INDEPENDENT
        }
        val duplicate = independent.groupingBy { it.caseId to it.annotatorId }
            .eachCount().any { it.value > 1 }
        if (duplicate) {
            return AgreementResult(null, "anotação duplicada; κ não calculado", 0)
        }

        val byCase = independent.groupBy { it.caseId }
        val pairCounts = mutableMapOf<Pair<String, String>, MutableList<List<Trust2B2Annotation>>>()
        byCase.forEach { (_, list) ->
            if (list.map { it.annotatorId }.distinct().size == 2) {
                val pair = list.map { it.annotatorId }.sorted()
                pairCounts.getOrPut(pair[0] to pair[1]) { mutableListOf() } += list
            }
        }
        if (pairCounts.isEmpty()) {
            return AgreementResult(
                null,
                "sem dois anotadores independentes por caso",
                0,
            )
        }
        val chosen = pairCounts.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, MutableList<List<Trust2B2Annotation>>>> { it.value.size }
                .thenBy { it.key.first }
                .thenBy { it.key.second })
            .first()
        val pair = chosen.key
        val cases = chosen.value
        if (cases.size < 2) {
            return AgreementResult(
                null,
                "amostra insuficiente para Cohen's κ (mínimo 2 casos do mesmo par)",
                cases.size,
                "${pair.first}|${pair.second}",
            )
        }
        val labelsA = cases.map { list -> list.first { it.annotatorId == pair.first }.semanticState }
        val labelsB = cases.map { list -> list.first { it.annotatorId == pair.second }.semanticState }
        val n = cases.size
        val po = labelsA.indices.count { labelsA[it] == labelsB[it] }.toDouble() / n
        val categories = (labelsA + labelsB).distinct()
        val pe = categories.sumOf { category ->
            val pA = labelsA.count { it == category }.toDouble() / n
            val pB = labelsB.count { it == category }.toDouble() / n
            pA * pB
        }
        if (pe >= 1.0) {
            return AgreementResult(
                null,
                "sem variação de rótulos; κ indefinido",
                n,
                "${pair.first}|${pair.second}",
            )
        }
        return AgreementResult(
            kappa = (po - pe) / (1.0 - pe),
            reason = if (pairCounts.size > 1) {
                "pares adicionais excluídos para manter identidade dos revisores"
            } else {
                "calculado com distribuições marginais por anotador"
            },
            annotatedCases = n,
            reviewerPair = "${pair.first}|${pair.second}",
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
        val verifiedCases = cases.count { it.preparationStatus == PreparationStatus.VERIFIED }
        val limitations = buildList {
            if (verifiedCases == 0) {
                add("Nenhum caso com fonte real verificada nesta execução.")
            } else {
                add("$verifiedCases caso(s) com fonte real verificada; não generalizar.")
            }
            if (humanAnnotated == 0) {
                add("Nenhuma anotação humana real nesta execução.")
            } else {
                add("$humanAnnotated caso(s) com anotação humana; segunda anotação/κ dependem dos dados.")
            }
            if (agreement.kappa == null) {
                add("Cohen's κ indisponível: ${agreement.reason}.")
            } else {
                add("Cohen's κ calculável com ${agreement.annotatedCases} caso(s); amostra pequena exige cautela.")
            }
            add("Macro-F1 ${b2b1.macroF1() ?: "n/d"} é do baseline sintético B2B1, não desempenho real.")
            add("T2-01…T2-15 continuam sem especificação verificável na main.")
            add("Fonte disponível não prova que o Mentor a utilizou.")
        }
        return Trust2B2Report(
            totalPending = cases.size,
            preparedCases = cases.count { it.preparationStatus != PreparationStatus.INVENTORIED },
            classifiedCases = cases.count { it.classificationStatus == ClassificationStatus.CLASSIFIED },
            pendingClassification = cases.count { it.classificationStatus == ClassificationStatus.PENDING },
            pilotCases = cases.count { it.isPilot },
            verifiedCases = verifiedCases,
            humanAnnotatedCases = humanAnnotated,
            secondAnnotationPending = secondPending,
            divergences = divergences,
            kappa = agreement.kappa,
            kappaReason = agreement.reason,
            reviewerPair = agreement.reviewerPair,
            coverageByRoute = cases.groupingBy { it.route }.eachCount(),
            coverageByClaimType = cases.mapNotNull { it.expectedClaimType }
                .groupingBy { it }.eachCount(),
            missingFieldCounts = cases.flatMap { it.missingFields }
                .groupingBy { it }.eachCount(),
            pilotGoals = Trust2B2Inventory.pilotGoals(),
            t2Gap = Trust2B1Dataset.gaps,
            validationErrors = validateInventory(cases) + validateAnnotations(cases, annotations),
            b2b1Metrics = b2b1,
            limitations = limitations,
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
        appendLine("- Casos classificados: ${report.classifiedCases}")
        appendLine("- Casos com classificação pendente: ${report.pendingClassification}")
        appendLine("- Casos-piloto: ${report.pilotCases}")
        appendLine("- Casos verificados com fonte real: ${report.verifiedCases}")
        appendLine("- Casos com anotação humana: ${report.humanAnnotatedCases}")
        appendLine("- Aguardando segunda anotação: ${report.secondAnnotationPending}")
        appendLine("- Divergências: ${report.divergences}")
        appendLine("- Cohen's κ: ${report.kappa ?: "n/d"} (${report.kappaReason})")
        appendLine("- Par de revisores: ${report.reviewerPair ?: "n/d"}")
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
