package com.bettertalker.app.data.copilot

/**
 * TRUST-2A — contratos puros de verificação semântica.
 *
 * Esta etapa NÃO executa verificação semântica em produção, não chama LLM e
 * não altera rotas. Apenas define como representar afirmações, evidências
 * rastreáveis, fidelidade literal e resultados de avaliação.
 *
 * Invariante central: nenhum construtor atribui SUPPORTED por padrão. A
 * existência de uma referência ou de uma citação não é prova de suporte
 * factual.
 */

/** Tipo de suporte exigido por uma afirmação. */
enum class SupportEvidence {
    DOCUMENTARY,
    METHOD,
    NONE,
}

/** Tipo de conteúdo de uma afirmação. */
enum class ClaimType(val supportEvidence: SupportEvidence) {
    FACTUAL_BIBLICAL(SupportEvidence.DOCUMENTARY),
    FACTUAL_DOCTRINAL(SupportEvidence.DOCUMENTARY),
    FACTUAL_HISTORICAL(SupportEvidence.DOCUMENTARY),
    ORATORY_TECHNIQUE(SupportEvidence.METHOD),
    USER_HYPOTHESIS(SupportEvidence.NONE),
    LITERAL_CITATION(SupportEvidence.NONE),
    CREATIVE_SUGGESTION(SupportEvidence.NONE),
    STRUCTURAL_EDITORIAL(SupportEvidence.NONE);

    val requiresDocumentarySupport: Boolean
        get() = supportEvidence == SupportEvidence.DOCUMENTARY
}

/** Intervalo de uma afirmação no texto original da resposta. */
data class ClaimSpan(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "start não pode ser negativo" }
        require(endExclusive > start) { "endExclusive deve ser maior que start" }
    }
}

/** Referência citada/associada a uma afirmação. */
data class ClaimReference(
    val raw: String,
    /** Documento recuperado associado, quando houver. Null = não recuperado. */
    val documentId: String? = null,
) {
    init {
        require(raw.isNotBlank()) { "raw não pode ser vazio" }
        require(documentId == null || documentId.isNotBlank()) {
            "documentId não pode ser vazio quando presente"
        }
    }
}

/** Afirmação extraída de uma resposta. */
data class Claim(
    val id: String,
    val text: String,
    val span: ClaimSpan,
    val type: ClaimType,
    val references: List<ClaimReference> = emptyList(),
    val candidateEvidenceIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "id não pode ser vazio" }
        require(text.isNotBlank()) { "text não pode ser vazio" }
        require(candidateEvidenceIds.none { it.isBlank() }) {
            "candidateEvidenceIds não pode conter IDs vazios"
        }
    }
}

/** Completude da recuperação documental. */
enum class EvidenceCompleteness {
    COMPLETE,
    PARTIAL,
    UNKNOWN,
}

/** Tipo de documento de uma unidade verificável. */
enum class DocumentKind(
    val requiredCategory: EvidenceCategory,
    val documentary: Boolean,
    val requiresUnit: Boolean,
) {
    BIBLE(EvidenceCategory.CONTENT, documentary = true, requiresUnit = true),
    PUBLICATION(EvidenceCategory.CONTENT, documentary = true, requiresUnit = true),
    S34(EvidenceCategory.STRUCTURAL, documentary = false, requiresUnit = false),
    USER_MESSAGE(EvidenceCategory.CONVERSATION, documentary = false, requiresUnit = false),
    TRAINING(EvidenceCategory.TRAINING, documentary = false, requiresUnit = false),
}

/**
 * Unidade documental verificável. Reutiliza [EvidenceItem] e preserva
 * identidade, origem, unidade e texto efetivamente recuperado.
 */
data class EvidenceUnit(
    val item: EvidenceItem,
    val kind: DocumentKind,
    val edition: String? = null,
    val documentUnit: String? = null,
    val truncated: Boolean = false,
    /** Completude explícita; UNKNOWN nunca é promovido a COMPLETE. */
    val completeness: EvidenceCompleteness = EvidenceCompleteness.UNKNOWN,
    /**
     * Indica que este texto foi efetivamente disponibilizado ao contexto do
     * Mentor. Recuperar não é o mesmo que expor ao modelo.
     */
    val exposedToModel: Boolean = false,
) {
    init {
        require(item.provenance.category == kind.requiredCategory) {
            "kind ${kind.name} exige categoria ${kind.requiredCategory.name}"
        }
    }

    val documentId: String get() = item.id
    val reference: String get() = item.reference
    val text: String get() = item.text
    val hasDocumentUnit: Boolean get() = !documentUnit.isNullOrBlank()

    /**
     * Elegível como prova factual: documento bíblico/publicação, resolvido,
     * não dispensado, texto não vazio, sem truncamento, completo, com unidade
     * documental e efetivamente exposto ao modelo.
     */
    fun isFactualEligible(): Boolean =
        kind.documentary &&
            item.isDocumentaryEvidence() &&
            !truncated &&
            completeness == EvidenceCompleteness.COMPLETE &&
            hasDocumentUnit &&
            exposedToModel

    /** Elegível como orientação de método/oratória, nunca como prova factual. */
    fun isMethodEligible(): Boolean =
        kind == DocumentKind.TRAINING &&
            item.isMethodEvidence() &&
            !truncated &&
            completeness == EvidenceCompleteness.COMPLETE &&
            exposedToModel
}

/** Adaptador de [RecoveredDocument] para [EvidenceUnit], sem duplicar texto. */
fun RecoveredDocument.asEvidenceUnit(
    kind: DocumentKind,
    edition: String? = null,
    truncated: Boolean = false,
    completeness: EvidenceCompleteness = when {
        partial -> EvidenceCompleteness.PARTIAL
        resolved -> EvidenceCompleteness.COMPLETE
        else -> EvidenceCompleteness.UNKNOWN
    },
    exposedToModel: Boolean = false,
): EvidenceUnit = EvidenceUnit(
    item = EvidenceItem(
        id = sourceId,
        reference = reference,
        provenance = EvidenceProvenance(
            sourceId = sourceId,
            category = kind.requiredCategory,
            documentUnit = documentUnit,
            recoveredText = text,
            resolved = resolved,
            waived = waived,
        ),
    ),
    kind = kind,
    edition = edition,
    documentUnit = documentUnit,
    truncated = truncated,
    completeness = completeness,
    exposedToModel = exposedToModel,
)

/** Estado semântico de uma afirmação. */
enum class SemanticState {
    SUPPORTED,
    INSUFFICIENT,
    CONTRADICTED,
    UNVERIFIABLE,
    NOT_EVALUATED,
}

/**
 * Resultado da avaliação de uma afirmação.
 *
 * SUPPORTED exige pelo menos uma unidade examinada identificável.
 */
data class ClaimVerdict(
    val claimId: String,
    val state: SemanticState,
    val examinedEvidenceIds: List<String> = emptyList(),
    val confidence: Double? = null,
    val reason: String? = null,
) {
    init {
        require(claimId.isNotBlank()) { "claimId não pode ser vazio" }
        require(confidence == null || confidence in 0.0..1.0) {
            "confidence deve estar entre 0 e 1"
        }
        require(examinedEvidenceIds.none { it.isBlank() }) {
            "examinedEvidenceIds não pode conter IDs vazios"
        }
        require(examinedEvidenceIds.distinct().size == examinedEvidenceIds.size) {
            "examinedEvidenceIds não pode conter duplicatas"
        }
        if (state == SemanticState.SUPPORTED) {
            require(examinedEvidenceIds.isNotEmpty()) {
                "SUPPORTED exige evidência examinada identificável"
            }
        }
        if (state == SemanticState.NOT_EVALUATED) {
            require(examinedEvidenceIds.isEmpty()) {
                "NOT_EVALUATED não deve declarar evidência examinada"
            }
        }
    }

    companion object {
        fun notEvaluated(claimId: String): ClaimVerdict =
            ClaimVerdict(claimId = claimId, state = SemanticState.NOT_EVALUATED)
    }
}

/**
 * Associação imutável entre afirmação, veredito e unidades examinadas.
 * Garante que IDs examinados existam na lista de evidências e que SUPPORTED
 * só ocorra em afirmação factual com evidência documental elegível.
 */
data class ClaimAssessment(
    val claim: Claim,
    val verdict: ClaimVerdict,
    val evidence: List<EvidenceUnit> = emptyList(),
) {
    init {
        require(verdict.claimId == claim.id) {
            "verdict.claimId deve corresponder ao claim.id"
        }
        val ids = evidence.map { it.documentId }
        require(ids.distinct().size == ids.size) {
            "evidence não pode conter documentId duplicado"
        }
        val availableIds = ids.toSet()
        require(verdict.examinedEvidenceIds.all { it in availableIds }) {
            "verdict cita evidência que não está em evidence"
        }
        if (verdict.state == SemanticState.SUPPORTED) {
            val examined = examinedEvidence()
            when (claim.type.supportEvidence) {
                SupportEvidence.DOCUMENTARY ->
                    require(examined.any { it.isFactualEligible() }) {
                        "SUPPORTED factual exige evidência documental elegível entre as examinadas"
                    }
                SupportEvidence.METHOD ->
                    require(examined.any { it.isMethodEligible() }) {
                        "SUPPORTED de oratória exige evidência de método elegível entre as examinadas"
                    }
                SupportEvidence.NONE -> throw IllegalArgumentException(
                    "SUPPORTED não é permitido para ${claim.type.name}"
                )
            }
        }
    }

    /** Unidades efetivamente examinadas, na ordem dos IDs do veredito. */
    fun examinedEvidence(): List<EvidenceUnit> =
        verdict.examinedEvidenceIds.mapNotNull { id ->
            evidence.firstOrNull { it.documentId == id }
        }
}

/**
 * Resultado agregado. O estado global é conservador e nunca promove um
 * conjunto não avaliado a SUPPORTED.
 */
data class SemanticVerificationResult(
    val assessments: List<ClaimAssessment> = emptyList(),
) {
    val evaluatedAssessments: List<ClaimAssessment>
        get() = assessments.filter { it.verdict.state != SemanticState.NOT_EVALUATED }

    fun overallState(): SemanticState {
        if (assessments.isEmpty()) return SemanticState.NOT_EVALUATED
        val states = assessments.map { it.verdict.state }
        return when {
            SemanticState.CONTRADICTED in states -> SemanticState.CONTRADICTED
            SemanticState.INSUFFICIENT in states -> SemanticState.INSUFFICIENT
            SemanticState.UNVERIFIABLE in states -> SemanticState.UNVERIFIABLE
            SemanticState.NOT_EVALUATED in states -> SemanticState.NOT_EVALUATED
            else -> SemanticState.SUPPORTED
        }
    }

    fun hasDocumentarySupport(): Boolean =
        assessments.isNotEmpty() &&
            assessments.all { assessment ->
                assessment.verdict.state == SemanticState.SUPPORTED &&
                    assessment.claim.type.supportEvidence == SupportEvidence.DOCUMENTARY &&
                    assessment.examinedEvidence().any { it.isFactualEligible() }
            }
}

/** Origem de uma citação literal. */
enum class CitationOrigin {
    DOCUMENT,
    USER_MESSAGE,
    S34,
}

/** Resultado de fidelidade literal, separado de suporte factual. */
enum class CitationFidelityState {
    EXACT,
    NORMALIZED,
    NOT_FOUND,
    UNVERIFIABLE,
    NOT_EVALUATED,
}

/**
 * Fidelidade literal de uma citação. Nunca autoriza, por si só, uma
 * afirmação factual: `authorizesFactualClaim` é sempre falso.
 */
data class CitationFidelity(
    val citation: String,
    val origin: CitationOrigin,
    val state: CitationFidelityState,
    val sourceId: String? = null,
    val documentUnit: String? = null,
    val matchedText: String? = null,
    val reason: String? = null,
) {
    init {
        if (state == CitationFidelityState.EXACT ||
            state == CitationFidelityState.NORMALIZED
        ) {
            require(!sourceId.isNullOrBlank()) {
                "fidelidade encontrada exige sourceId"
            }
            require(!matchedText.isNullOrBlank()) {
                "fidelidade encontrada exige matchedText"
            }
        }
    }

    /** Uma citação fiel não é, por si só, prova factual. */
    val authorizesFactualClaim: Boolean get() = false
}

/** Normaliza apenas espaços/pontuação/tipografia, sem alterar palavras. */
internal fun normalizeLiteralText(text: String): String = text
    .replace(Regex("\\s+"), " ")
    .replace('“', '"')
    .replace('”', '"')
    .replace('‘', '\'')
    .replace('’', '\'')
    .replace(Regex("\\s*[\\u2010-\\u2015-]\\s*"), "-")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun literalMatch(
    citation: String,
    sourceText: String,
): Pair<CitationFidelityState, String?> {
    val trimmed = citation.trim()
    if (trimmed.isBlank()) {
        return CitationFidelityState.NOT_EVALUATED to null
    }
    if (sourceText.isBlank()) {
        return CitationFidelityState.UNVERIFIABLE to null
    }
    if (sourceText.contains(trimmed)) {
        return CitationFidelityState.EXACT to trimmed
    }
    val normalizedCitation = normalizeLiteralText(trimmed)
    val normalizedSource = normalizeLiteralText(sourceText)
    if (normalizedCitation.isNotBlank() && normalizedSource.contains(normalizedCitation)) {
        return CitationFidelityState.NORMALIZED to trimmed
    }
    return CitationFidelityState.NOT_FOUND to null
}

/**
 * Fidelidade de citação documental contra UMA unidade. Não junta textos de
 * documentos/unidades diferentes.
 */
fun checkDocumentCitation(
    citation: String,
    evidence: EvidenceUnit,
): CitationFidelity {
    if (citation.isBlank()) {
        return CitationFidelity(
            citation = citation,
            origin = CitationOrigin.DOCUMENT,
            state = CitationFidelityState.NOT_EVALUATED,
        )
    }
    val provenance = evidence.item.provenance
    if (!evidence.kind.documentary) {
        return CitationFidelity(
            citation = citation,
            origin = CitationOrigin.DOCUMENT,
            state = CitationFidelityState.UNVERIFIABLE,
            sourceId = evidence.documentId,
            documentUnit = evidence.documentUnit,
            reason = "unidade não é documental",
        )
    }
    if (!provenance.resolved || provenance.waived) {
        return CitationFidelity(
            citation = citation,
            origin = CitationOrigin.DOCUMENT,
            state = CitationFidelityState.UNVERIFIABLE,
            sourceId = evidence.documentId,
            documentUnit = evidence.documentUnit,
            reason = if (provenance.waived) {
                "fonte dispensada"
            } else {
                "fonte não resolvida"
            },
        )
    }
    if (evidence.truncated || evidence.completeness != EvidenceCompleteness.COMPLETE) {
        return CitationFidelity(
            citation = citation,
            origin = CitationOrigin.DOCUMENT,
            state = CitationFidelityState.UNVERIFIABLE,
            sourceId = evidence.documentId,
            documentUnit = evidence.documentUnit,
            reason = "fonte truncada ou incompleta: fidelidade não conclusiva",
        )
    }
    val (state, matched) = literalMatch(citation, evidence.text)
    return CitationFidelity(
        citation = citation,
        origin = CitationOrigin.DOCUMENT,
        state = state,
        sourceId = evidence.documentId,
        documentUnit = evidence.documentUnit,
        matchedText = matched,
    )
}

/** Fidelidade de citação atribuída ao usuário. */
fun checkUserCitation(
    citation: String,
    attributed: AttributedUserQuote,
    sourceId: String,
): CitationFidelity {
    if (citation.isBlank()) {
        return CitationFidelity(
            citation = citation,
            origin = CitationOrigin.USER_MESSAGE,
            state = CitationFidelityState.NOT_EVALUATED,
            sourceId = sourceId,
        )
    }
    val (state, matched) = literalMatch(citation, attributed.trustedOriginalMessage)
    return CitationFidelity(
        citation = citation,
        origin = CitationOrigin.USER_MESSAGE,
        state = if (attributed.isGroundedInMessage()) state else CitationFidelityState.NOT_FOUND,
        sourceId = sourceId,
        matchedText = matched,
    )
}

/** Fidelidade de citação do S-34 fornecido. Nunca vira prova factual. */
fun checkS34Citation(
    citation: String,
    s34Text: String,
    sourceId: String,
): CitationFidelity {
    if (citation.isBlank()) {
        return CitationFidelity(
            citation = citation,
            origin = CitationOrigin.S34,
            state = CitationFidelityState.NOT_EVALUATED,
            sourceId = sourceId,
        )
    }
    val (state, matched) = literalMatch(citation, s34Text)
    return CitationFidelity(
        citation = citation,
        origin = CitationOrigin.S34,
        state = state,
        sourceId = sourceId,
        matchedText = matched,
    )
}
