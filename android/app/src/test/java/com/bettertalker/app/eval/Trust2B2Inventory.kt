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
                val (status, type) = classificationFor(seed.caseId)
                val source = truthSourceFor(status, type)
                val required = requiredFieldsFor(source)
                Trust2B2Case(
                    caseId = seed.caseId,
                    route = seed.route,
                    summary = seed.summary,
                    classificationStatus = status,
                    expectedClaimType = type,
                    truthSource = source,
                    requiredEvidenceFields = required,
                    missingFields = required,
                    preparationStatus = preparationStatusFor(source),
                    evidenceAvailability = EvidenceAvailability.NOT_AVAILABLE_IN_REPO,
                    evaluationCriterion = criterionFor(status, type),
                    requiresHumanAnnotation = true,
                    evidenceEligibleForSupport = false,
                    evidenceTruncated = false,
                    evidenceExposedToModel = false,
                    evidenceMetadata = emptyList(),
                    falsePositiveRisk = falsePositiveRisk(status, type),
                    falseNegativeRisk = falseNegativeRisk(status, type),
                    isPilot = seed.caseId in pilotIds,
                    notes = "Preparado para avaliação real; fonte não disponível no repositório.",
                )
            }

    /**
     * Objetivos corrigidos para os 11 IDs históricos. Cenários de roteamento,
     * marcadores, SourceGate e integridade permanecem como testes de
     * comportamento, não como afirmações bíblicas.
     */
    fun pilotGoals(): List<PilotGoal> = listOf(
        PilotGoal("Citação literal inexistente/entre aspas", listOf("C02"), PreparationStatus.NEEDS_SOURCE, "Requer texto documental real para comparar a citação."),
        PilotGoal("Número não autorizado dentro de sugestão", listOf("C06"), PreparationStatus.NEEDS_CLAIM, "Classificar se o número é dado factual ou técnica antes de anotar."),
        PilotGoal("Contagem técnica “3 passos” em sugestão", listOf("C07"), PreparationStatus.NEEDS_CLAIM, "Cenário de unidade técnica; não é afirmação bíblica."),
        PilotGoal("Versículo citado na prosa normal", listOf("C08"), PreparationStatus.NEEDS_SOURCE, "Requer texto TNM real e unidade do versículo."),
        PilotGoal("Fonte waived em pergunta ampla", listOf("C10"), PreparationStatus.NEEDS_SOURCE, "Requer fonte real e decisão de escopo."),
        PilotGoal("Fonte disponível sem marcador [Fonte N]", listOf("G02"), PreparationStatus.NEEDS_CLAIM, "Teste de contrato de citação, não de exegese."),
        PilotGoal("Fonte disponível com frase de falta de acesso", listOf("G03"), PreparationStatus.NEEDS_CLAIM, "Teste de política de resposta; não é claim bíblica."),
        PilotGoal("Marcador [Fonte 5] fora do intervalo (duas fontes)", listOf("G04"), PreparationStatus.NEEDS_CLAIM, "Teste de intervalo de citação."),
        PilotGoal("Sem fontes com [Fonte 1]", listOf("G05"), PreparationStatus.NEEDS_CLAIM, "Teste de fonte inexistente."),
        PilotGoal("be/th usada como prova doutrinal", listOf("G07"), PreparationStatus.NEEDS_SOURCE, "be/th só pode fundamentar método, nunca doutrina."),
        PilotGoal("Histórico com afirmação falsa", listOf("G08"), PreparationStatus.NEEDS_ANNOTATION, "Requer mensagem histórica original e rótulo de contexto."),
    )

    /**
     * Propostas futuras sem ID histórico compatível. Não reutilizam IDs.
     */
    fun futureEvaluationProposals(): List<String> = listOf(
        "Paráfrase correta — requer novo cenário com claim e fonte real.",
        "Afirmação contradita pela fonte — requer novo cenário com unidade contraditória.",
        "Edição incorreta — requer novo cenário com duas edições reais.",
        "Interpretação que extrapola o texto — requer novo cenário com interpretação candidata.",
    )

    private fun classificationFor(id: String): Pair<ClassificationStatus, ClaimType?> = when (id) {
        "C02" -> ClassificationStatus.CLASSIFIED to ClaimType.LITERAL_CITATION
        "C08", "C10" -> ClassificationStatus.CLASSIFIED to ClaimType.FACTUAL_BIBLICAL
        "G07" -> ClassificationStatus.CLASSIFIED to ClaimType.FACTUAL_DOCTRINAL
        "G08" -> ClassificationStatus.CLASSIFIED to ClaimType.USER_HYPOTHESIS
        "S01" -> ClassificationStatus.CLASSIFIED to ClaimType.FACTUAL_BIBLICAL
        "S05" -> ClassificationStatus.CLASSIFIED to ClaimType.LITERAL_CITATION
        "K03", "K04", "K05" -> ClassificationStatus.CLASSIFIED to ClaimType.FACTUAL_BIBLICAL
        "K08" -> ClassificationStatus.CLASSIFIED to ClaimType.LITERAL_CITATION
        "D02", "D03", "D04", "D05" -> ClassificationStatus.CLASSIFIED to ClaimType.FACTUAL_BIBLICAL
        "D06" -> ClassificationStatus.CLASSIFIED to ClaimType.FACTUAL_DOCTRINAL
        "V02", "V03", "V04" -> ClassificationStatus.CLASSIFIED to ClaimType.LITERAL_CITATION
        "V06" -> ClassificationStatus.CLASSIFIED to ClaimType.FACTUAL_BIBLICAL
        "T1A-01", "T1A-02" -> ClassificationStatus.CLASSIFIED to ClaimType.USER_HYPOTHESIS
        "T1A-03", "T1A-04" -> ClassificationStatus.CLASSIFIED to ClaimType.STRUCTURAL_EDITORIAL
        else -> ClassificationStatus.PENDING to null
    }

    private fun truthSourceFor(
        status: ClassificationStatus,
        type: ClaimType?,
    ): TruthSourceType {
        if (status == ClassificationStatus.PENDING || type == null) {
            return TruthSourceType.PENDING
        }
        return when (type) {
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
        TruthSourceType.PENDING -> listOf(
            "claimClassification", "evaluationCriterion", "humanAnnotation",
        )
    }

    private fun preparationStatusFor(source: TruthSourceType): PreparationStatus =
        when (source) {
            TruthSourceType.FACTUAL_DOCUMENT,
            TruthSourceType.STRUCTURE,
            TruthSourceType.METHOD,
            -> PreparationStatus.NEEDS_SOURCE
            TruthSourceType.USER_CONTEXT -> PreparationStatus.NEEDS_ANNOTATION
            TruthSourceType.NONE -> PreparationStatus.NEEDS_ANNOTATION
            TruthSourceType.PENDING -> PreparationStatus.NEEDS_CLAIM
        }

    private fun criterionFor(
        status: ClassificationStatus,
        type: ClaimType?,
    ): String {
        if (status == ClassificationStatus.PENDING || type == null) {
            return "Classificar o cenário antes de tratá-lo como claim semântica; não assumir fato bíblico."
        }
        return when (type) {
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
    }

    private fun falsePositiveRisk(
        status: ClassificationStatus,
        type: ClaimType?,
    ): String = when {
        status == ClassificationStatus.PENDING ->
            "Classificar comportamento técnico como afirmação bíblica."
        type == ClaimType.ORATORY_TECHNIQUE ->
            "Tratar orientação de método como doutrina."
        type == ClaimType.USER_HYPOTHESIS ->
            "Tratar hipótese do usuário como evidência."
        type == ClaimType.STRUCTURAL_EDITORIAL ->
            "Tratar estrutura do S-34 como fonte documental."
        else ->
            "Aceitar trecho apenas relacionado como se implicasse a afirmação."
    }

    private fun falseNegativeRisk(
        status: ClassificationStatus,
        type: ClaimType?,
    ): String = when {
        status == ClassificationStatus.PENDING ->
            "Ignorar que o cenário é apenas técnico e não exige suporte factual."
        type == ClaimType.ORATORY_TECHNIQUE ->
            "Rejeitar orientação de método legítima por falta de fonte doutrinal."
        type == ClaimType.USER_HYPOTHESIS ->
            "Exigir prova documental de uma hipótese que deve permanecer hipótese."
        type == ClaimType.STRUCTURAL_EDITORIAL ->
            "Exigir fonte documental para conteúdo que é apenas estrutural."
        else ->
            "Rejeitar paráfrase fiel por falta de correspondência literal."
    }
}
