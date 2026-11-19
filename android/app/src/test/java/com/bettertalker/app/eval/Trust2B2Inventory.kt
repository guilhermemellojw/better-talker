package com.bettertalker.app.eval

import com.bettertalker.app.data.copilot.ClaimType

/**
 * Camada TRUST-2B2 sobre o inventário TRUST-2B1.
 *
 * Não altera os 79 casos nem os rótulos sintéticos. Apenas prepara os 48
 * casos NEEDS_ANNOTATION para futura avaliação com fontes reais.
 */
object Trust2B2Inventory {

    val pilotIds: Set<String> = setOf(
        "C02", "C06", "C07", "C08", "C10",
        "G02", "G03", "G04", "G05", "G07", "G08",
    )

    fun cases(): List<Trust2B2Case> =
        Trust2B1Dataset.inventory()
            .filter { it.status == DatasetStatus.NEEDS_ANNOTATION }
            .map { seed ->
                val type = expectedClaimType(seed.caseId)
                val source = truthSourceFor(type)
                val required = requiredFieldsFor(source)
                Trust2B2Case(
                    caseId = seed.caseId,
                    route = seed.route,
                    summary = seed.summary,
                    expectedClaimType = type,
                    truthSource = source,
                    requiredEvidenceFields = required,
                    missingFields = required,
                    preparationStatus = preparationStatusFor(source),
                    evidenceAvailability = EvidenceAvailability.NOT_AVAILABLE_IN_REPO,
                    evaluationCriterion = criterionFor(type),
                    requiresHumanAnnotation = true,
                    evidenceEligibleForSupport = false,
                    falsePositiveRisk = falsePositiveRisk(type),
                    falseNegativeRisk = falseNegativeRisk(type),
                    isPilot = seed.caseId in pilotIds,
                    notes = "Preparado para avaliação real; fonte não disponível no repositório.",
                )
            }

    fun pilotGoals(): List<PilotGoal> = listOf(
        PilotGoal("Citação bíblica literal", listOf("C02"), PreparationStatus.NEEDS_SOURCE, "Requer texto TNM legítimo e unidade."),
        PilotGoal("Paráfrase correta", listOf("G02"), PreparationStatus.NEEDS_SOURCE, "Requer trecho real e anotação humana."),
        PilotGoal("Paráfrase sem apoio", listOf("C10"), PreparationStatus.NEEDS_SOURCE, "Requer fonte real e limite de escopo."),
        PilotGoal("Afirmação contradita", listOf("C08"), PreparationStatus.NEEDS_SOURCE, "Requer trecho que contradiga a afirmação."),
        PilotGoal("Fonte ausente", listOf("G03"), PreparationStatus.NEEDS_SOURCE, "Requer cenário sem fonte e resposta honesta."),
        PilotGoal("Edição incorreta", listOf("G04"), PreparationStatus.NEEDS_SOURCE, "Requer duas edições reais para comparar."),
        PilotGoal("Citação inventada", listOf("G05"), PreparationStatus.NEEDS_SOURCE, "Requer referência real e texto ausente."),
        PilotGoal("Interpretação que extrapola", listOf("C06"), PreparationStatus.NEEDS_SOURCE, "Requer trecho literal e interpretação candidata."),
        PilotGoal("be/th como prova doutrinal", listOf("G07"), PreparationStatus.NEEDS_SOURCE, "be/th só pode fundamentar método."),
        PilotGoal("Hipótese do usuário", listOf("G08"), PreparationStatus.NEEDS_ANNOTATION, "Requer mensagem original e anotação de contexto."),
        PilotGoal("Número/técnica em sugestão", listOf("C07"), PreparationStatus.NEEDS_ANNOTATION, "Requer decisão sobre técnica vs dado factual."),
    )

    private fun expectedClaimType(id: String): ClaimType = when (id) {
        "C06", "K08" -> ClaimType.FACTUAL_BIBLICAL
        "C07", "V05", "SG05", "R03", "T1A-06" -> ClaimType.ORATORY_TECHNIQUE
        "G04", "G05", "V01", "V02", "V03", "V04", "V07" -> ClaimType.LITERAL_CITATION
        "G07", "D06" -> ClaimType.FACTUAL_DOCTRINAL
        "G08", "T1A-01", "T1A-02" -> ClaimType.USER_HYPOTHESIS
        "S04", "K06", "V08", "SG06", "T1A-03", "T1A-04", "T1A-08" ->
            ClaimType.STRUCTURAL_EDITORIAL
        else -> ClaimType.FACTUAL_BIBLICAL
    }

    private fun truthSourceFor(type: ClaimType): TruthSourceType = when (type) {
        ClaimType.FACTUAL_BIBLICAL,
        ClaimType.FACTUAL_DOCTRINAL,
        ClaimType.FACTUAL_HISTORICAL,
        ClaimType.LITERAL_CITATION,
        -> TruthSourceType.FACTUAL_DOCUMENT
        ClaimType.ORATORY_TECHNIQUE -> TruthSourceType.METHOD
        ClaimType.USER_HYPOTHESIS -> TruthSourceType.USER_CONTEXT
        ClaimType.STRUCTURAL_EDITORIAL -> TruthSourceType.STRUCTURE
        ClaimType.CREATIVE_SUGGESTION -> TruthSourceType.NONE
    }

    private fun requiredFieldsFor(source: TruthSourceType): List<String> = when (source) {
        TruthSourceType.FACTUAL_DOCUMENT -> listOf(
            "documentId", "publication", "edition", "documentUnit",
            "recoveredText", "recoveryState", "exposedToModel",
            "premiseIds", "humanAnnotation",
        )
        TruthSourceType.METHOD -> listOf(
            "methodDocumentId", "methodUnit", "methodText",
            "premiseIds", "humanAnnotation",
        )
        TruthSourceType.USER_CONTEXT -> listOf(
            "userMessageId", "userMessageText", "humanAnnotation",
        )
        TruthSourceType.STRUCTURE -> listOf(
            "structureDocumentId", "structureUnit", "contextText", "humanAnnotation",
        )
        TruthSourceType.NONE -> listOf("humanAnnotation")
    }

    private fun preparationStatusFor(source: TruthSourceType): PreparationStatus =
        when (source) {
            TruthSourceType.FACTUAL_DOCUMENT,
            TruthSourceType.STRUCTURE,
            -> PreparationStatus.NEEDS_SOURCE
            TruthSourceType.METHOD -> PreparationStatus.NEEDS_SOURCE
            TruthSourceType.USER_CONTEXT -> PreparationStatus.NEEDS_ANNOTATION
            TruthSourceType.NONE -> PreparationStatus.NEEDS_ANNOTATION
        }

    private fun criterionFor(type: ClaimType): String = when (type) {
        ClaimType.FACTUAL_BIBLICAL,
        ClaimType.FACTUAL_DOCTRINAL,
        ClaimType.FACTUAL_HISTORICAL,
        -> "Comparar a afirmação com unidade documental real; sem fonte verificada, não pode ser SUPPORTED."
        ClaimType.LITERAL_CITATION ->
            "Verificar fidelidade literal contra documentId; fidelidade não prova interpretação factual."
        ClaimType.ORATORY_TECHNIQUE ->
            "be/th pode fundamentar apenas técnica; nunca prova doutrinal."
        ClaimType.USER_HYPOTHESIS ->
            "Mensagem do usuário é contexto conversacional; nunca prova factual."
        ClaimType.STRUCTURAL_EDITORIAL ->
            "S-34 é estrutura; nunca prova factual."
        ClaimType.CREATIVE_SUGGESTION ->
            "Sugestão criativa identificada; referências e números ainda exigem apoio."
    }

    private fun falsePositiveRisk(type: ClaimType): String = when (type) {
        ClaimType.ORATORY_TECHNIQUE ->
            "Tratar orientação de método como doutrina."
        ClaimType.USER_HYPOTHESIS ->
            "Tratar hipótese do usuário como evidência."
        ClaimType.STRUCTURAL_EDITORIAL ->
            "Tratar estrutura do S-34 como fonte documental."
        else ->
            "Aceitar trecho apenas relacionado como se implicasse a afirmação."
    }

    private fun falseNegativeRisk(type: ClaimType): String = when (type) {
        ClaimType.ORATORY_TECHNIQUE ->
            "Rejeitar orientação de método legítima por falta de fonte doutrinal."
        ClaimType.USER_HYPOTHESIS ->
            "Exigir prova documental de uma hipótese que deve permanecer hipótese."
        ClaimType.STRUCTURAL_EDITORIAL ->
            "Exigir fonte documental para conteúdo que é apenas estrutural."
        else ->
            "Rejeitar paráfrase fiel por falta de correspondência literal."
    }
}
