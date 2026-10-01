package com.bettertalker.app.ui.editor

/**
 * Estado de prontidão da nota para o onboarding contextual do Copilot.
 *
 * Lido on-demand pelo FAB (F2) para decidir o que mostrar: setup
 * (importar esboço / baixar bases) vs. desenvolvimento de seção.
 *
 * F1 do onboarding contextual — sem UI, sem persistência.
 */
data class NoteReadiness(
    /** Nota tem pelo menos uma seção (INTRO/BODY/CONCLUSION). */
    val hasSections: Boolean,
    /** Nota tem esboço vinculado. */
    val hasOutline: Boolean,
    /** Há referências citadas ainda não resolvidas (refs faltando). */
    val hasMissingRefs: Boolean,
    /** Há publicações/bases ainda não baixadas. */
    val hasMissingBases: Boolean,
    /** Nota tem histórico de chat (pelo menos 1 mensagem). */
    val hasChatHistory: Boolean,
) {
    /**
     * Setup está completo quando há para onde ir (esboço ou seções)
     * e nada bloqueando (bases baixadas).
     *
     * Refs faltando ficam FORA da fórmula: são estado normal de
     * trabalho (o Copilot ajuda a resolver/baixar), não setup
     * incompleto. Histórico também fica fora (é sinal para o
     * greeting, não para setup).
     */
    val isSetupComplete: Boolean
        get() = (hasOutline || hasSections) && !hasMissingBases
}
