package com.bettertalker.app.ui.hub

/** Ambiente que recebe a nota atual; Treino e Palco continuam independentes. */
enum class HubContinuityTarget { TRAIN, PRESENT }

data class HubContinuitySelections(
    val trainNoteId: String? = null,
    val presentNoteId: String? = null,
)

/**
 * Candidato ao atalho "Usar esboço atual": só devolve uma nota que ainda
 * existe na lista ativa. Nota excluída, na lixeira ou inexistente → null.
 * Puro/testável.
 */
fun hubCurrentFallback(
    cards: List<HubNoteCard>,
    lastNoteId: String?,
): HubNoteCard? {
    val id = lastNoteId?.takeIf { it.isNotBlank() } ?: return null
    return cards.firstOrNull { it.id == id }
}

/**
 * Aplica a nota atual apenas no ambiente pedido. Não toca no outro ambiente
 * e não age se o ambiente já tiver seleção (evita substituição silenciosa).
 * Puro/testável.
 */
fun hubUseCurrent(
    target: HubContinuityTarget,
    currentNoteId: String?,
    lastNoteId: String?,
    selections: HubContinuitySelections,
): HubContinuitySelections? {
    if (currentNoteId != null) return null
    val id = lastNoteId?.takeIf { it.isNotBlank() } ?: return null
    return when (target) {
        HubContinuityTarget.TRAIN -> selections.copy(trainNoteId = id)
        HubContinuityTarget.PRESENT -> selections.copy(presentNoteId = id)
    }
}
