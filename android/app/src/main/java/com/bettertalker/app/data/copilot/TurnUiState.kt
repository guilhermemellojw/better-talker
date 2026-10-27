package com.bettertalker.app.data.copilot

/**
 * M1 — estado de UM turno de chat.
 *
 * A UI renderiza um único item por turno, com `turnId` estável, do WAITING ao
 * DONE/CANCELLED/ERROR. O parcial não é uma mensagem separada: ele é o corpo
 * do mesmo item. Puro/testável.
 */
enum class TurnPhase {
    WAITING,
    REASONING,
    STREAMING,
    DONE,
    CANCELLED,
    ERROR,
}

data class TurnUiState(
    val turnId: String,
    val phase: TurnPhase = TurnPhase.WAITING,
    /** Texto parcial congelado no cancelamento ou final no DONE. */
    val partialText: String = "",
    val reasoningText: String = "",
    val reasoningStartedAt: Long? = null,
    val reasoningEndedAt: Long? = null,
    val finalText: String = "",
    val finalMessageId: String? = null,
    val error: String? = null,
    val retryNotice: String? = null,
    val startedAt: Long = 0L,
) {
    val isLive: Boolean
        get() = phase == TurnPhase.WAITING ||
            phase == TurnPhase.REASONING ||
            phase == TurnPhase.STREAMING

    val isTerminal: Boolean
        get() = phase == TurnPhase.DONE ||
            phase == TurnPhase.CANCELLED ||
            phase == TurnPhase.ERROR

    /**
     * Atualiza o corpo do turno a partir do streaming do provider. Nunca
     * regride um turno terminal (DONE/CANCELLED/ERROR).
     */
    fun withProgress(
        partial: String,
        reasoning: String,
        retryNotice: String?,
        now: Long,
    ): TurnUiState {
        if (isTerminal) return this
        val hasPartial = partial.isNotBlank()
        val hasReasoning = reasoning.isNotBlank()
        val nextPhase = when {
            hasPartial -> TurnPhase.STREAMING
            hasReasoning -> TurnPhase.REASONING
            else -> TurnPhase.WAITING
        }
        val started = reasoningStartedAt
            ?: now.takeIf { hasReasoning }
        val ended = reasoningEndedAt
            ?: now.takeIf { hasPartial && hasReasoning }
        return copy(
            phase = nextPhase,
            partialText = partial,
            reasoningText = reasoning,
            reasoningStartedAt = started,
            reasoningEndedAt = ended,
            retryNotice = retryNotice,
        )
    }

    /** Final chegou: o mesmo item passa a DONE. */
    fun done(messageId: String, text: String, now: Long): TurnUiState {
        if (phase == TurnPhase.CANCELLED || phase == TurnPhase.ERROR) return this
        return copy(
            phase = TurnPhase.DONE,
            finalMessageId = messageId,
            finalText = text,
            partialText = text,
            reasoningEndedAt = reasoningEndedAt
                ?: now.takeIf { reasoningText.isNotBlank() },
            retryNotice = null,
        )
    }

    /** Stop: congela o parcial/reasoning; não apaga o que já foi lido. */
    fun cancelled(): TurnUiState {
        if (phase == TurnPhase.DONE) return this
        return copy(
            phase = TurnPhase.CANCELLED,
            retryNotice = null,
        )
    }

    fun failed(message: String): TurnUiState {
        if (phase == TurnPhase.DONE) return this
        return copy(
            phase = TurnPhase.ERROR,
            error = message,
            retryNotice = null,
        )
    }

    /** "Pensou por Xs" — arredonda para cima e nunca mostra 0s. */
    fun reasoningSeconds(now: Long = System.currentTimeMillis()): Int? {
        val start = reasoningStartedAt ?: return null
        val end = reasoningEndedAt ?: now
        val ms = (end - start).coerceAtLeast(0)
        return ((ms + 999) / 1000).toInt().coerceAtLeast(1)
    }
}

/**
 * M1.1 — payload do turno cancelado persistido. Mantém o parcial, o
 * `turnId` e os segundos de raciocínio depois de fechar/reabrir o app.
 * Puro/testável.
 */
fun TurnUiState.cancelledPayload(now: Long = System.currentTimeMillis()): Map<String, String> =
    buildMap {
        put("text", partialText)
        put("origin", "COPILOT")
        put("turnId", turnId)
        put("cancelled", "1")
        put("partial", "1")
        reasoningSeconds(now)?.takeIf { it > 0 }?.let {
            put("reasoningSeconds", it.toString())
        }
    }
