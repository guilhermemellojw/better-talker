package com.bettertalker.app.eval

import com.bettertalker.app.data.copilot.ClaimType
import com.bettertalker.app.data.copilot.CitationFidelityState
import com.bettertalker.app.data.copilot.DocumentKind
import com.bettertalker.app.data.copilot.EvidenceCompleteness
import com.bettertalker.app.data.copilot.SemanticState

/** Origem de verdade exigida por um caso experimental. */
enum class TruthSourceType {
    FACTUAL_DOCUMENT,
    METHOD,
    USER_CONTEXT,
    STRUCTURE,
    NONE,
    PENDING,
}

/** Estado da classificação semântica do cenário histórico. */
enum class ClassificationStatus {
    CLASSIFIED,
    PENDING,
}

/** Estado de preparação — distinto de SemanticState. */
enum class PreparationStatus {
    INVENTORIED,
    NEEDS_SOURCE,
    NEEDS_CLAIM,
    NEEDS_PREMISE,
    NEEDS_ANNOTATION,
    READY_FOR_REVIEW,
    VERIFIED,
}

/** Disponibilidade da fonte no ambiente experimental. */
enum class EvidenceAvailability {
    NOT_ATTEMPTED,
    NOT_AVAILABLE_IN_REPO,
    METADATA_ONLY,
    LOCAL_SOURCE_AVAILABLE,
    VERIFIED_LOCAL,
}

/** Estado da recuperação documental. */
enum class RecoveryStatus {
    NOT_ATTEMPTED,
    MISSING,
    PARTIAL,
    COMPLETE,
    UNKNOWN,
}

/** Origem de uma anotação. */
enum class AnnotationSource {
    HUMAN_INDEPENDENT,
    HUMAN_ADJUDICATOR,
    SYNTHETIC,
    SCRIPT,
}

/**
 * Metadados experimentais de proveniência. Não armazenam texto protegido;
 * apenas identificam a unidade e o estado de recuperação.
 */
data class Trust2B2EvidenceMetadata(
    val documentId: String,
    val documentKind: DocumentKind,
    val publicationReference: String? = null,
    val edition: String? = null,
    val documentUnit: String,
    val premiseId: String,
    val recoveryStatus: RecoveryStatus,
    val completeness: EvidenceCompleteness,
    val truncated: Boolean,
    val exposedToModel: Boolean,
    val availability: EvidenceAvailability,
    val truthSource: TruthSourceType,
) {
    init {
        require(documentId.isNotBlank()) { "documentId não pode ser vazio" }
        require(documentUnit.isNotBlank()) { "documentUnit não pode ser vazio" }
        require(premiseId.isNotBlank()) { "premiseId não pode ser vazio" }
    }

    fun isVerifiedDocumentary(expectedEdition: String?): Boolean =
        truthSource == TruthSourceType.FACTUAL_DOCUMENT &&
            documentKind in setOf(DocumentKind.BIBLE, DocumentKind.PUBLICATION) &&
            availability == EvidenceAvailability.VERIFIED_LOCAL &&
            recoveryStatus == RecoveryStatus.COMPLETE &&
            completeness == EvidenceCompleteness.COMPLETE &&
            !truncated &&
            exposedToModel &&
            (expectedEdition == null || edition == expectedEdition)

    fun isVerifiedMethod(): Boolean =
        truthSource == TruthSourceType.METHOD &&
            documentKind == DocumentKind.TRAINING &&
            availability == EvidenceAvailability.VERIFIED_LOCAL &&
            recoveryStatus == RecoveryStatus.COMPLETE &&
            completeness == EvidenceCompleteness.COMPLETE &&
            !truncated &&
            exposedToModel
}

/**
 * Caso do dataset real preparado pela TRUST-2B2.
 *
 * Não contém texto de publicação nem IDs de documento fictícios. Quando a
 * fonte não está disponível localmente, o caso permanece pendente.
 */
data class Trust2B2Case(
    val caseId: String,
    val route: EvalRoute,
    val summary: String,
    val classificationStatus: ClassificationStatus,
    val expectedClaimType: ClaimType?,
    val truthSource: TruthSourceType,
    val requiredEvidenceFields: List<String>,
    val missingFields: List<String>,
    val preparationStatus: PreparationStatus,
    val evidenceAvailability: EvidenceAvailability,
    val evaluationCriterion: String,
    val requiresHumanAnnotation: Boolean = true,
    val evidenceEligibleForSupport: Boolean = false,
    val evidenceTruncated: Boolean = false,
    val evidenceExposedToModel: Boolean = false,
    val expectedEdition: String? = null,
    val evidenceMetadata: List<Trust2B2EvidenceMetadata> = emptyList(),
    val falsePositiveRisk: String,
    val falseNegativeRisk: String,
    val isPilot: Boolean = false,
    val notes: String? = null,
) {
    init {
        require(caseId.isNotBlank()) { "caseId não pode ser vazio" }
        require(summary.isNotBlank()) { "summary não pode ser vazio" }
        require(requiredEvidenceFields.none { it.isBlank() }) {
            "requiredEvidenceFields não pode conter campos vazios"
        }
        require(missingFields.none { it.isBlank() }) {
            "missingFields não pode conter campos vazios"
        }
        require(requiredEvidenceFields.distinct().size == requiredEvidenceFields.size) {
            "requiredEvidenceFields não pode conter duplicatas"
        }
        require(missingFields.distinct().size == missingFields.size) {
            "missingFields não pode conter duplicatas"
        }
        require(!(preparationStatus == PreparationStatus.VERIFIED && missingFields.isNotEmpty())) {
            "caso VERIFIED não pode ter campos ausentes"
        }
        require(!(preparationStatus == PreparationStatus.VERIFIED &&
            evidenceAvailability != EvidenceAvailability.VERIFIED_LOCAL)) {
            "caso VERIFIED exige fonte local verificada"
        }
        if (expectedClaimType == null) {
            require(classificationStatus == ClassificationStatus.PENDING) {
                "claim nulo exige classificação PENDING"
            }
            require(truthSource == TruthSourceType.PENDING) {
                "claim nulo exige truthSource PENDING"
            }
        } else {
            require(classificationStatus == ClassificationStatus.CLASSIFIED) {
                "claim definido exige classificação CLASSIFIED"
            }
            require(truthSource != TruthSourceType.PENDING) {
                "claim definido não pode ter truthSource PENDING"
            }
        }
        if (evidenceEligibleForSupport) {
            require(evidenceMetadata.any { it.isVerifiedDocumentary(expectedEdition) }) {
                "suporte elegível exige metadados documentais verificados"
            }
        }
        require(evidenceMetadata.map { it.premiseId }.distinct().size == evidenceMetadata.size) {
            "premiseId duplicado nos metadados"
        }
        require(evidenceMetadata.map { it.documentId }.distinct().size == evidenceMetadata.size) {
            "documentId duplicado nos metadados"
        }
    }
}

/**
 * Anotação humana ou automática. Anotação humana exige identificação real do
 * anotador; script/IA nunca é contado como anotação humana independente.
 */
data class Trust2B2Annotation(
    val caseId: String,
    val annotatorId: String,
    val semanticState: SemanticState,
    val citationFidelity: CitationFidelityState?,
    val premiseIds: List<String>,
    val rationale: String,
    val source: AnnotationSource,
) {
    init {
        require(caseId.isNotBlank()) { "caseId não pode ser vazio" }
        if (source == AnnotationSource.HUMAN_INDEPENDENT ||
            source == AnnotationSource.HUMAN_ADJUDICATOR
        ) {
            require(annotatorId.isNotBlank()) { "anotação humana exige annotatorId" }
            require(rationale.isNotBlank()) { "anotação humana exige justificativa" }
        }
        require(premiseIds.none { it.isBlank() }) {
            "premiseIds não pode conter IDs vazios"
        }
        if (semanticState == SemanticState.SUPPORTED ||
            semanticState == SemanticState.CONTRADICTED
        ) {
            require(premiseIds.isNotEmpty()) { "${semanticState.name} exige premissa vinculada" }
        }
    }

    val isHuman: Boolean
        get() = source == AnnotationSource.HUMAN_INDEPENDENT ||
            source == AnnotationSource.HUMAN_ADJUDICATOR
}

data class AgreementResult(
    val kappa: Double?,
    val reason: String,
    val annotatedCases: Int,
    val reviewerPair: String? = null,
)

data class PilotGoal(
    val goal: String,
    val candidateCaseIds: List<String>,
    val status: PreparationStatus,
    val note: String,
)

data class Trust2B2Report(
    val totalPending: Int,
    val preparedCases: Int,
    val classifiedCases: Int,
    val pendingClassification: Int,
    val pilotCases: Int,
    val verifiedCases: Int,
    val humanAnnotatedCases: Int,
    val secondAnnotationPending: Int,
    val divergences: Int,
    val kappa: Double?,
    val kappaReason: String,
    val reviewerPair: String?,
    val coverageByRoute: Map<EvalRoute, Int>,
    val coverageByClaimType: Map<ClaimType, Int>,
    val missingFieldCounts: Map<String, Int>,
    val pilotGoals: List<PilotGoal>,
    val t2Gap: List<String>,
    val validationErrors: List<String>,
    val b2b1Metrics: EvalMetrics,
    val limitations: List<String>,
)
