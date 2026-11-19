package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionRelationship
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SupersessionConfirmation
import com.bettertalker.app.data.copilot.SupersessionResult
import com.bettertalker.app.data.copilot.classifyDecisionRelationship
import com.bettertalker.app.data.copilot.hasStableOriginIdentity
import com.bettertalker.app.data.repo.DecisionMemoryRepository

/** Estado em memória da pergunta de substituição; não persiste "manter as duas". */
data class DecisionSupersessionPrompt(
    val confirmationId: String,
    val noteId: String,
    val previous: DecisionRecord,
    val replacement: DecisionRecord,
    val isSubmitting: Boolean = false,
    val outcome: DecisionSupersessionOutcome? = null,
    val message: String? = null,
)

enum class DecisionSupersessionOutcome {
    SUCCESS,
    ALREADY_APPLIED,
    STALE,
    CONFLICT,
    INELIGIBLE,
    AMBIGUOUS,
    NOT_FOUND,
    ERROR,
    REFRESH_UNAVAILABLE,
}

/**
 * 1C.2.1B2C — transforma o classificador conservador em pergunta.
 *
 * Só `PotentialOverlap` e `LegacyPotentialOverlap` geram prompt; o restante
 * mantém as decisões como estão. A função é pura e não grava nada.
 */
fun buildDecisionSupersessionPrompt(
    newDecision: DecisionRecord,
    history: List<DecisionRecord>,
): DecisionSupersessionPrompt? {
    if (newDecision.decisionState != DecisionState.ACTIVE) return null
    val withoutNew = history.filter { it.id != newDecision.id }
    val candidate = when (
        val relationship = classifyDecisionRelationship(newDecision, withoutNew)
    ) {
        is DecisionRelationship.PotentialOverlap -> relationship.candidate
        is DecisionRelationship.LegacyPotentialOverlap -> relationship.candidate
        else -> return null
    }
    // A B2B não executa substituição sem proveniência estável. Não mostrar
    // uma pergunta que não poderá ser concluída com segurança.
    if (
        !newDecision.hasStableOriginIdentity() ||
        !candidate.hasStableOriginIdentity()
    ) {
        return null
    }
    return DecisionSupersessionPrompt(
        confirmationId = "supconf-${newDecision.id}-${candidate.id}",
        noteId = newDecision.noteId,
        previous = candidate,
        replacement = newDecision,
    )
}

fun DecisionSupersessionPrompt.toConfirmation(
    now: Long,
): SupersessionConfirmation = SupersessionConfirmation(
    confirmationId = confirmationId,
    noteId = noteId,
    previousDecisionId = previous.id,
    replacementDecisionId = replacement.id,
    expectedPreviousUpdatedAt = previous.updatedAt,
    expectedPreviousState = DecisionState.ACTIVE,
    confirmedAt = now,
)

fun DecisionSupersessionPrompt.startSubmission(): DecisionSupersessionPrompt =
    copy(isSubmitting = true, outcome = null, message = null)

fun DecisionSupersessionPrompt.withUnexpectedError(): DecisionSupersessionPrompt =
    copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.ERROR,
        message = "Não consegui concluir agora. As duas decisões continuam salvas.",
    )

fun DecisionSupersessionPrompt.withRefreshUnavailable(): DecisionSupersessionPrompt =
    copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.REFRESH_UNAVAILABLE,
        message = "As decisões mudaram. As duas continuam salvas.",
    )

/** Mensagens simples; nunca afirmam que o texto do discurso mudou. */
fun DecisionSupersessionPrompt.withResult(
    result: SupersessionResult,
): DecisionSupersessionPrompt = when (result) {
    is SupersessionResult.Committed -> copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.SUCCESS,
        message = "Pronto. A nova decisão agora vale no lugar da anterior. " +
            "O texto do discurso não foi alterado.",
    )

    is SupersessionResult.AlreadyApplied -> copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.ALREADY_APPLIED,
        message = "A nova decisão já estava no lugar da anterior.",
    )

    is SupersessionResult.Stale -> copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.STALE,
        message = "A decisão anterior mudou desde que esta pergunta apareceu. " +
            "Atualize os dados e tente de novo.",
    )

    is SupersessionResult.Conflict -> copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.CONFLICT,
        message = "Não foi possível usar a nova decisão no lugar da anterior. " +
            "As duas continuam salvas.",
    )

    is SupersessionResult.Ineligible -> copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.INELIGIBLE,
        message = "Essa substituição não está mais disponível. " +
            "As duas decisões continuam salvas.",
    )

    is SupersessionResult.Ambiguous -> copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.AMBIGUOUS,
        message = "Não consegui confirmar com segurança qual decisão substituir. " +
            "As duas continuam salvas.",
    )

    is SupersessionResult.NotFound -> copy(
        isSubmitting = false,
        outcome = DecisionSupersessionOutcome.NOT_FOUND,
        message = "Uma das decisões não está mais disponível.",
    )
}

fun DecisionSupersessionPrompt.isFinished(): Boolean =
    outcome == DecisionSupersessionOutcome.SUCCESS ||
        outcome == DecisionSupersessionOutcome.ALREADY_APPLIED ||
        outcome == DecisionSupersessionOutcome.CONFLICT ||
        outcome == DecisionSupersessionOutcome.INELIGIBLE ||
        outcome == DecisionSupersessionOutcome.AMBIGUOUS ||
        outcome == DecisionSupersessionOutcome.NOT_FOUND ||
        outcome == DecisionSupersessionOutcome.REFRESH_UNAVAILABLE

fun DecisionSupersessionPrompt.canRefresh(): Boolean =
    outcome == DecisionSupersessionOutcome.STALE

fun DecisionSupersessionPrompt.canRetry(): Boolean =
    outcome == DecisionSupersessionOutcome.ERROR

/**
 * 1C.2.1B2C — supressão por sessão, em memória.
 *
 * Evita repetir a mesma pergunta por recomposição ou por um novo aceite que
 * reconstrua o mesmo par. Não persiste "Manter as duas": encerrada a sessão,
 * a próxima abertura do app pode perguntar de novo.
 */
class DecisionSupersessionSession {
    private val suppressed = mutableSetOf<String>()

    fun shouldOffer(prompt: DecisionSupersessionPrompt?): Boolean =
        prompt != null && prompt.confirmationId !in suppressed

    fun suppress(prompt: DecisionSupersessionPrompt) {
        suppressed.add(prompt.confirmationId)
    }
}

/** 1C.2.1B2C — consulta e executa a substituição de uma decisão já persistida. */
class DecisionSupersessionService(
    private val decisionMemory: DecisionMemoryRepository,
) {

    suspend fun findPrompt(newDecision: DecisionRecord): DecisionSupersessionPrompt? =
        buildDecisionSupersessionPrompt(
            newDecision = newDecision,
            history = decisionMemory.forNote(newDecision.noteId),
        )

    suspend fun confirm(
        prompt: DecisionSupersessionPrompt,
        now: Long = System.currentTimeMillis(),
    ): SupersessionResult = decisionMemory.supersedePersistedReplacementConfirmed(
        confirmation = prompt.toConfirmation(now),
        now = now,
    )

    /**
     * Relê o estado atual e reconstrói a pergunta. Retorna `null` quando o
     * relacionamento deixou de existir ou os registros não estão mais
     * disponíveis; o chamador então apenas informa e mantém as duas.
     */
    suspend fun refresh(prompt: DecisionSupersessionPrompt): DecisionSupersessionPrompt? {
        val currentPrevious = decisionMemory.get(prompt.previous.id) ?: return null
        val currentReplacement = decisionMemory.get(prompt.replacement.id) ?: return null
        if (currentPrevious.decisionState != DecisionState.ACTIVE) return null
        val refreshed = buildDecisionSupersessionPrompt(
            newDecision = currentReplacement,
            history = decisionMemory.forNote(prompt.noteId),
        ) ?: return null
        if (refreshed.previous.id != currentPrevious.id) return null
        return refreshed
    }
}
