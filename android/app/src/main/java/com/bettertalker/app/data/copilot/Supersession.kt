package com.bettertalker.app.data.copilot

/**
 * 1C.2.1B2B — confirmação EXPLÍCITA de que uma decisão substitui outra.
 *
 * Este contrato só deve ser construído pela futura B2C depois de uma escolha
 * humana clara ("usar a nova no lugar da anterior"). Um
 * [DecisionRelationship.PotentialOverlap] NÃO é confirmação; ele apenas indica
 * um candidato. Similaridade textual, sugestão do Mentor, classificação
 * automática ou processamento em background também não são confirmação.
 *
 * [confirmationId] identifica a ação de UI (telemetria/testes). A idempotência
 * persistente é definida pelo par `(previousDecisionId, replacementDecisionId)`;
 * não há coluna nova para o id da confirmação.
 *
 * [expectedPreviousUpdatedAt] é o estado que o usuário viu. Se a decisão
 * anterior mudou depois disso, a operação retorna [SupersessionResult.Stale].
 */
data class SupersessionConfirmation(
    val confirmationId: String,
    val noteId: String,
    val previousDecisionId: String,
    val replacementDecisionId: String,
    val expectedPreviousUpdatedAt: Long,
    val expectedPreviousState: DecisionState = DecisionState.ACTIVE,
    val confirmedAt: Long,
)

/** Resultado tipado de uma tentativa de substituição confirmada. */
sealed interface SupersessionResult {

    /** As duas linhas foram gravadas no mesmo commit. */
    data class Committed(
        val previous: DecisionRecord,
        val replacement: DecisionRecord,
    ) : SupersessionResult

    /** A mesma operação já estava persistida; nada foi reescrito. */
    data class AlreadyApplied(
        val previous: DecisionRecord,
        val replacement: DecisionRecord,
    ) : SupersessionResult

    data class Conflict(val reason: SupersessionConflictReason) : SupersessionResult

    data class Ineligible(val reason: SupersessionIneligibleReason) : SupersessionResult

    data class Stale(
        val previousDecisionId: String,
        val expectedUpdatedAt: Long,
        val actualUpdatedAt: Long,
    ) : SupersessionResult

    data class NotFound(val decisionId: String) : SupersessionResult

    /**
     * Identidade ou relacionamento insuficiente para uma transição segura.
     * Nunca deve ser convertido em substituição automática.
     */
    data class Ambiguous(val reason: SupersessionAmbiguityReason) : SupersessionResult
}

enum class SupersessionConflictReason {
    /** Outra substituição já venceu a corrida pela mesma decisão anterior. */
    PREVIOUS_SUPERSEDED_BY_OTHER,

    /** O id da substituta já existe e não representa esta operação. */
    REPLACEMENT_ID_COLLISION,

    /** O id da substituta já existe, mas o conteúdo/identidade diverge. */
    REPLACEMENT_PAYLOAD_DIVERGED,

    /** A substituta já aponta para outra decisão anterior. */
    REPLACEMENT_ALREADY_LINKED,

    /** As duas decisões compartilham a mesma origem/proposta. */
    ORIGIN_COLLISION,

    /** NoteId/proposta/ids da confirmação não batem com os registros. */
    CONFIRMATION_MISMATCH,

    /** A atualização condicional perdeu a corrida. */
    CONCURRENT_SUPERSESSION,
}

enum class SupersessionIneligibleReason {
    PREVIOUS_NOT_ACTIVE,
    REPLACEMENT_NOT_ACTIVE,
    NOT_CONFIRMED,
    DIFFERENT_NOTE,
    DIFFERENT_SCOPE,
    DIFFERENT_SECTION,
    DIFFERENT_ORIGINAL_SECTION,
    INVALID_SECTION_LINK,
    DIFFERENT_SUBJECT,
    RELATIONSHIP_NOT_ELIGIBLE,
    INVALID_CONFIRMATION,
    INVALID_TRANSITION,
}

enum class SupersessionAmbiguityReason {
    MISSING_STABLE_ORIGIN,
    UNKNOWN_SUBJECT,
    RELATIONSHIP_AMBIGUOUS,
    IDENTITY_AMBIGUOUS,
}

/**
 * 1C.2.1B2B — política conservadora de identidade de origem.
 *
 * Só permite transição persistente quando existe ao menos uma proveniência
 * estável: `sourceProposalId` (aceite estrutural B1) ou `sourceMessageId`
 * (evento explícito futuro). Decisões sem nenhuma das duas ficam ambíguas e
 * NÃO podem ser substituídas até uma política explícita (B2C/B3).
 *
 * Não usa texto, `createdAt`, `sectionId` atual ou similaridade como
 * identidade. Registros legítimos da B1 não são invalidados: eles têm
 * `sourceProposalId`.
 */
fun DecisionRecord.hasStableOriginIdentity(): Boolean =
    !sourceMessageId.isNullOrBlank() || !sourceProposalId.isNullOrBlank()

/**
 * Duas decisões não podem compartilhar a mesma origem/proposta: isso indica
 * evento duplicado, não substituição. A checagem é conservadora e usa apenas
 * proveniência não nula.
 */
internal fun hasOriginCollision(
    previous: DecisionRecord,
    replacement: DecisionRecord,
): Boolean =
    (previous.sourceProposalId != null &&
        previous.sourceProposalId == replacement.sourceProposalId) ||
        (previous.sourceMessageId != null &&
            previous.sourceMessageId == replacement.sourceMessageId)

/*
 * Notas para B2C:
 *
 * - A UI deve construir SupersessionConfirmation APENAS após a escolha
 *   "usar a nova no lugar da anterior". "Manter as duas" não chama a B2B.
 * - O texto do editor não é reescrito pela substituição do ledger. A UI não
 *   deve afirmar que o discurso mudou.
 * - A decisão SUPERSEDED deixa de ser memória ativa, mas preserva
 *   applicationState/appliedAt/appliedHash e snapshots.
 * - PotentialOverlap, LegacyPotentialOverlap, Ambiguous e Ineligible não são
 *   autorização. A confirmação é um contrato separado.
 */
