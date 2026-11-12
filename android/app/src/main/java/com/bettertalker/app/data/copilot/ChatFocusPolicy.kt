package com.bettertalker.app.data.copilot

/**
 * 6.0a/foco — decide a lente ao ENTRAR no chat.
 *
 * Regras:
 * - push novo do FAB/✨ vence e é consumido uma vez;
 * - sem push, se o chat foi aberto a partir do editor, usa a seção ativa;
 * - qualquer outro caso (menu/Assistente, nota trocada, seção inexistente)
 *   abre no discurso inteiro — nunca reaproveita a última seção persistida.
 *
 * Puro/testável. A rotação/recomposição é tratada por `sessionKey`: a mesma
 * sessão não reaplica a entrada.
 */
internal data class ChatEntryFocus(
    /** O push pendente deve ser mantido; a entrada não mexe no foco. */
    val keepPushed: Boolean = false,
    /** Seção ativa (null = discurso inteiro). */
    val sectionId: String? = null,
) {
    val whole: Boolean get() = sectionId == null && !keepPushed
}

internal fun resolveChatEntryFocus(
    hasPendingPush: Boolean,
    activeSectionId: String?,
    guideSectionIds: Set<String>,
    openedFromEditor: Boolean,
): ChatEntryFocus = when {
    hasPendingPush -> ChatEntryFocus(keepPushed = true)
    openedFromEditor &&
        activeSectionId != null &&
        activeSectionId in guideSectionIds -> ChatEntryFocus(sectionId = activeSectionId)
    else -> ChatEntryFocus(sectionId = null)
}

/** Mesma sessão = rotação/recomposição, não reaplica a entrada. */
internal fun isNewChatEntrySession(lastSessionKey: String?, sessionKey: String): Boolean =
    lastSessionKey != sessionKey
