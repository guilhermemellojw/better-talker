package com.bettertalker.app.ui.editor

/**
 * #7d4 — cálculo puro do índice alvo do D&D de seções.
 *
 * Conta quantos cards começam ACIMA de [draggedY] (topo do card arrastado, em
 * coordenadas de janela), sobre a ordem atual [orderedSectionIds]. O próprio
 * card arrastado entra na contagem: soltar na posição original devolve o
 * próprio índice — que [SectionsController.moveSectionTo] trata como no-op.
 *
 * Retorna null quando faltam dados (lista vazia, id fora da lista ou algum Y
 * ausente em [sectionWinY]).
 */
internal fun computeTargetIndex(
    draggedY: Float,
    orderedSectionIds: List<String>,
    sectionWinY: Map<String, Float>,
    draggedId: String,
): Int? {
    if (orderedSectionIds.isEmpty() || draggedId !in orderedSectionIds) return null
    var above = 0
    for (id in orderedSectionIds) {
        val y = sectionWinY[id] ?: return null
        if (y < draggedY) above++
    }
    return above.coerceIn(0, orderedSectionIds.size - 1)
}
