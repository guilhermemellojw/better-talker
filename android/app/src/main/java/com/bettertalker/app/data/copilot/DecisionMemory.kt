package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole

/** Onde a decisão vale. */
enum class DecisionScope { NOTE, SECTION }

/** Natureza da decisão. Não é o ciclo de vida. */
enum class DecisionKind { CONFIRMED, PREFERENCE, REJECTED, OPEN }

/** Ciclo de vida da decisão. */
enum class DecisionState { ACTIVE, SUPERSEDED, RESOLVED, REMOVED }

/** Relação da decisão com uma seção persistida. */
enum class SectionLink { NOT_APPLICABLE, VALID, ORPHANED, PENDING_RECONFIRMATION }

/**
 * Relação com o texto aplicado. É independente da associação com a seção:
 * uma remoção reversível preserva a aplicação histórica.
 */
enum class ApplicationState { NOT_APPLIED, APPLIED, STALE }

/**
 * Origem explícita de uma decisão.
 *
 * Não existe origem para tag da IA, card aplicado ou cache legado.
 */
enum class DecisionSource { EXPLICIT_USER, STRUCTURE_ACCEPT, DECISION_CONFIRMATION }

/** Snapshot imutável usado na reconciliação de seções. */
data class SectionSnapshot(
    val id: String,
    val noteId: String,
    val role: SectionRole,
    val title: String,
    val order: Int,
    val contentHash: String,
) {
    init {
        require(id.isNotBlank()) { "section.id vazio" }
        require(noteId.isNotBlank()) { "section.noteId vazio" }
        require(title.isNotBlank()) { "section.title vazio" }
        require(order >= 0) { "section.order negativo" }
        require(contentHash.isNotBlank()) { "section.contentHash vazio" }
    }
}

/**
 * 1C.2.1A1 — registro puro de decisão.
 *
 * `sectionId` é a associação atual (ou a última conhecida em ORPHANED).
 * `originalSectionId` é imutável e serve ao histórico/reconciliação.
 */
data class DecisionRecord(
    val id: String,
    val noteId: String,
    val scope: DecisionScope,
    val kind: DecisionKind,
    val decisionState: DecisionState = DecisionState.ACTIVE,
    val sectionLink: SectionLink =
        if (scope == DecisionScope.NOTE) SectionLink.NOT_APPLICABLE else SectionLink.VALID,
    val applicationState: ApplicationState = ApplicationState.NOT_APPLIED,
    val text: String,
    val source: DecisionSource,
    val sectionId: String? = null,
    val originalSectionId: String? = null,
    val candidateSectionId: String? = null,
    val sectionTitleSnapshot: String? = null,
    val sectionRoleSnapshot: SectionRole? = null,
    val sectionOrderSnapshot: Int? = null,
    val sectionContentHashSnapshot: String? = null,
    val subjectKey: String? = null,
    val sourceMessageId: String? = null,
    val sourceProposalId: String? = null,
    val supersedesId: String? = null,
    val appliedAt: Long? = null,
    val appliedHash: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val removedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "decision.id vazio" }
        require(noteId.isNotBlank()) { "decision.noteId vazio" }
        require(text.isNotBlank()) { "decision.text vazio" }
        require(updatedAt >= createdAt) { "updatedAt anterior a createdAt" }
        require(subjectKey == null || subjectKey.isNotBlank()) { "subjectKey vazio" }
        require(sourceMessageId == null || sourceMessageId.isNotBlank()) {
            "sourceMessageId vazio"
        }
        require(sourceProposalId == null || sourceProposalId.isNotBlank()) {
            "sourceProposalId vazio"
        }
        require(supersedesId == null || supersedesId.isNotBlank()) { "supersedesId vazio" }
        require(supersedesId == null || supersedesId != id) {
            "uma decisão não pode substituir a si mesma"
        }

        when (scope) {
            DecisionScope.NOTE -> {
                require(sectionLink == SectionLink.NOT_APPLICABLE) {
                    "decisão NOTE exige SectionLink.NOT_APPLICABLE"
                }
                require(sectionId == null && originalSectionId == null) {
                    "decisão NOTE não pode ter sectionId/originalSectionId"
                }
                require(candidateSectionId == null) {
                    "decisão NOTE não pode ter candidateSectionId"
                }
                require(
                    sectionTitleSnapshot == null &&
                        sectionRoleSnapshot == null &&
                        sectionOrderSnapshot == null &&
                        sectionContentHashSnapshot == null
                ) {
                    "decisão NOTE não pode ter snapshot de seção"
                }
            }

            DecisionScope.SECTION -> {
                require(sectionLink != SectionLink.NOT_APPLICABLE) {
                    "decisão SECTION exige associação de seção"
                }
                require(!sectionId.isNullOrBlank()) {
                    "decisão SECTION exige sectionId"
                }
                require(!originalSectionId.isNullOrBlank()) {
                    "decisão SECTION exige originalSectionId"
                }
                require(!sectionTitleSnapshot.isNullOrBlank()) {
                    "decisão SECTION exige sectionTitleSnapshot"
                }
                require(sectionRoleSnapshot != null) {
                    "decisão SECTION exige sectionRoleSnapshot"
                }
                require(sectionOrderSnapshot != null && sectionOrderSnapshot >= 0) {
                    "decisão SECTION exige sectionOrderSnapshot válido"
                }
                require(!sectionContentHashSnapshot.isNullOrBlank()) {
                    "decisão SECTION exige sectionContentHashSnapshot"
                }
                when (sectionLink) {
                    SectionLink.VALID, SectionLink.ORPHANED ->
                        require(candidateSectionId == null) {
                            "candidateSectionId só existe em PENDING_RECONFIRMATION"
                        }

                    SectionLink.PENDING_RECONFIRMATION -> {
                        require(!candidateSectionId.isNullOrBlank()) {
                            "PENDING_RECONFIRMATION exige candidateSectionId"
                        }
                        require(candidateSectionId != sectionId) {
                            "candidateSectionId não pode ser o sectionId atual"
                        }
                    }

                    SectionLink.NOT_APPLICABLE ->
                        error("SECTION não pode ser NOT_APPLICABLE")
                }
            }
        }

        when (decisionState) {
            DecisionState.ACTIVE -> require(removedAt == null)
            DecisionState.SUPERSEDED -> {
                require(kind != DecisionKind.OPEN) { "OPEN não pode ser SUPERSEDED" }
                require(removedAt == null)
            }

            DecisionState.RESOLVED -> {
                require(kind == DecisionKind.OPEN) { "RESOLVED só existe para OPEN" }
                require(removedAt == null)
            }

            DecisionState.REMOVED -> {
                require(removedAt != null) { "REMOVED exige removedAt" }
            }
        }

        require(
            decisionState != DecisionState.REMOVED || removedAt!! >= createdAt,
        ) { "removedAt anterior a createdAt" }

        when (applicationState) {
            ApplicationState.NOT_APPLIED -> {
                require(appliedAt == null && appliedHash == null) {
                    "NOT_APPLIED não pode ter appliedAt/appliedHash"
                }
            }

            ApplicationState.APPLIED -> {
                require(kind == DecisionKind.CONFIRMED) {
                    "APPLIED só existe para CONFIRMED"
                }
                require(appliedAt != null && !appliedHash.isNullOrBlank()) {
                    "APPLIED exige appliedAt/appliedHash"
                }
            }

            ApplicationState.STALE -> {
                require(kind == DecisionKind.CONFIRMED) {
                    "STALE só existe para CONFIRMED"
                }
                require(appliedAt != null && !appliedHash.isNullOrBlank()) {
                    "STALE exige histórico de aplicação"
                }
            }
        }

        if (kind != DecisionKind.CONFIRMED) {
            require(applicationState == ApplicationState.NOT_APPLIED) {
                "$kind não pode ter aplicação de texto"
            }
        }

        if (source == DecisionSource.STRUCTURE_ACCEPT) {
            require(kind == DecisionKind.CONFIRMED) {
                "STRUCTURE_ACCEPT só confirma CONFIRMED"
            }
        }
    }
}

/** Resultado de uma substituição explícita. */
data class SupersededPair(
    val previous: DecisionRecord,
    val replacement: DecisionRecord,
)

/**
 * Transições explícitas do domínio. Nenhuma transição cria decisão a partir
 * de aplicação de conteúdo, tag de IA ou cache legado.
 */
object DecisionTransitions {

    /** Remoção reversível: muda apenas a associação, preservando a aplicação. */
    fun orphanForReversibleRemoval(decision: DecisionRecord, now: Long): DecisionRecord {
        require(decision.scope == DecisionScope.SECTION) {
            "só decisão SECTION pode ficar ORPHANED"
        }
        require(decision.decisionState != DecisionState.REMOVED) {
            "REMOVED é terminal"
        }
        require(decision.sectionLink != SectionLink.NOT_APPLICABLE) {
            "seção sem associação não pode ser órfã"
        }
        return decision.copy(
            sectionLink = SectionLink.ORPHANED,
            candidateSectionId = null,
            updatedAt = maxOf(now, decision.updatedAt),
        )
    }

    /** Sugere um candidato, mas não transfere a associação. */
    fun markPendingReconfirmation(
        decision: DecisionRecord,
        candidateSectionId: String,
        now: Long,
    ): DecisionRecord {
        require(decision.scope == DecisionScope.SECTION) {
            "só decisão SECTION pode aguardar reconfirmação"
        }
        require(decision.decisionState != DecisionState.REMOVED) {
            "REMOVED é terminal"
        }
        require(candidateSectionId.isNotBlank()) { "candidateSectionId vazio" }
        require(candidateSectionId != decision.sectionId) {
            "candidato não pode ser a associação atual"
        }
        return decision.copy(
            sectionLink = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = candidateSectionId,
            updatedAt = maxOf(now, decision.updatedAt),
        )
    }

    /**
     * Único caminho de remapeamento. Preserva `originalSectionId` e só marca
     * aplicação como STALE se o conteúdo do novo alvo não bater com o aplicado.
     */
    fun confirmRemap(
        decision: DecisionRecord,
        target: SectionSnapshot,
        now: Long,
    ): DecisionRecord {
        require(decision.scope == DecisionScope.SECTION) {
            "só decisão SECTION pode ser remapeada"
        }
        require(decision.decisionState != DecisionState.REMOVED) {
            "REMOVED é terminal"
        }
        require(target.noteId == decision.noteId) {
            "remapeamento não pode trocar de nota"
        }
        val application = if (
            decision.applicationState == ApplicationState.APPLIED &&
            decision.appliedHash != target.contentHash
        ) {
            ApplicationState.STALE
        } else {
            decision.applicationState
        }
        return decision.copy(
            sectionId = target.id,
            sectionLink = SectionLink.VALID,
            candidateSectionId = null,
            applicationState = application,
            updatedAt = maxOf(now, decision.updatedAt),
        )
    }

    /** Exclusão definitiva: estado terminal. Não use para remoção reversível. */
    fun removePermanently(decision: DecisionRecord, now: Long): DecisionRecord {
        if (decision.decisionState == DecisionState.REMOVED) return decision
        return decision.copy(
            decisionState = DecisionState.REMOVED,
            removedAt = now,
            updatedAt = maxOf(now, decision.updatedAt),
        )
    }

    /**
     * Valida a direção da substituição:
     * - apenas o novo registro aponta para o anterior em `supersedesId`;
     * - ambos pertencem à mesma nota e ao mesmo escopo/seção;
     * - o anterior está ACTIVE e não é OPEN;
     * - o novo está ACTIVE e não é OPEN.
     */
    fun validateSupersession(previous: DecisionRecord, replacement: DecisionRecord) {
        require(previous.id != replacement.id) {
            "decisão não pode substituir a si mesma"
        }
        require(replacement.supersedesId == previous.id) {
            "replacement.supersedesId deve apontar para previous.id"
        }
        require(previous.noteId == replacement.noteId) {
            "substituição não pode trocar de nota"
        }
        require(previous.scope == replacement.scope) {
            "substituição não pode trocar NOTE/SECTION"
        }
        require(previous.decisionState == DecisionState.ACTIVE) {
            "só decisão ACTIVE pode ser substituída"
        }
        require(replacement.decisionState == DecisionState.ACTIVE) {
            "replacement deve estar ACTIVE"
        }
        require(previous.kind != DecisionKind.OPEN) {
            "OPEN é RESOLVED, não SUPERSEDED"
        }
        require(replacement.kind != DecisionKind.OPEN) {
            "OPEN não substitui decisão anterior"
        }
        require(replacement.createdAt >= previous.createdAt) {
            "replacement anterior no tempo"
        }
        if (previous.scope == DecisionScope.SECTION) {
            require(previous.sectionId == replacement.sectionId) {
                "substituição deve ocorrer na mesma associação de seção"
            }
            require(previous.originalSectionId == replacement.originalSectionId) {
                "substituição deve preservar a mesma seção original"
            }
        }
    }

    fun supersede(
        previous: DecisionRecord,
        replacement: DecisionRecord,
        now: Long,
    ): SupersededPair {
        validateSupersession(previous, replacement)
        return SupersededPair(
            previous = previous.copy(
                decisionState = DecisionState.SUPERSEDED,
                updatedAt = maxOf(now, previous.updatedAt),
            ),
            replacement = replacement,
        )
    }
}
