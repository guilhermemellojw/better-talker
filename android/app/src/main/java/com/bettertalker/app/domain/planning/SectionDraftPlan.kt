package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.SectionRole

/**
 * M3/45 min — como uma seção será gerada.
 *
 * - [SINGLE]: uma chamada (30 min, intro/conclusão, demo).
 * - [BY_SUBPOINT]: a fila por sub-ponto já existente (20+ min com subpontos).
 * - [CONTINUATION]: BODY monolítico que não cabe em uma saída; partes
 *   internas com stitch no final.
 *
 * Puro/testável: sem Android, sem IO.
 */
enum class SectionDraftMode { SINGLE, BY_SUBPOINT, CONTINUATION }

/** Progresso de uma geração em partes (UI do DraftSheet). */
data class DraftProgress(
    val partIndex: Int,
    val partCount: Int,
    val targetWords: Int,
    val wordsSoFar: Int,
)

/** Especificação de uma parte interna entregue ao prompt. */
data class DraftPartSpec(
    val index: Int,
    val count: Int,
    val targetWords: Int,
    /** Final da parte anterior (texto puro) para continuar sem repetir. */
    val previousTail: String? = null,
    /** Continuação manual (não é a última parte planejada): não concluir. */
    val continuation: Boolean = false,
) {
    val isFirst: Boolean get() = index <= 1
    val isLast: Boolean get() = index >= count
}

data class SectionDraftPlan(
    val mode: SectionDraftMode,
    val parts: Int,
    val partMinutes: List<Int>,
    val partTargetWords: List<Int>,
) {
    init {
        require(parts >= 1) { "parts >= 1" }
        require(partMinutes.size == parts) { "partMinutes.size == parts" }
        require(partTargetWords.size == parts) { "partTargetWords.size == parts" }
    }

    val totalMinutes: Int get() = partMinutes.sum()
    val totalTargetWords: Int get() = partTargetWords.sum()
}

/**
 * Plano da seção.
 *
 * Regras fechadas:
 * - BODY sem subpontos, discurso, orçamento com mais de 1 parte → CONTINUATION.
 * - BODY com subpontos → BY_SUBPOINT (caminho atual; não orquestra aqui).
 * - 30 min → SINGLE. 45 min → 2 partes (23 + 22; 2.990 + 2.860 palavras).
 * - O resto da divisão vai para as primeiras partes; a soma NUNCA perde
 *   minuto (45/4 = 12+11+11+11, não 11×4).
 */
fun sectionDraftPlan(
    role: SectionRole,
    sectionMinutes: Int,
    kind: LongTextKind,
    hasSubPoints: Boolean,
    subPointCount: Int = 0,
    budget: LongTextBudget,
): SectionDraftPlan {
    val minutes = sectionMinutes.coerceAtLeast(1)
    if (role == SectionRole.BODY && hasSubPoints && subPointCount > 0) {
        val parts = subPointCount.coerceAtLeast(1)
        val mins = distributeMinutes(minutes, parts)
        return SectionDraftPlan(
            mode = SectionDraftMode.BY_SUBPOINT,
            parts = parts,
            partMinutes = mins,
            partTargetWords = mins.map { targetWordsFor(kind, it) },
        )
    }
    val continuation = role == SectionRole.BODY &&
        !hasSubPoints &&
        kind == LongTextKind.SPEECH &&
        budget.parts > 1
    if (continuation) {
        val parts = budget.parts.coerceAtLeast(2)
        val mins = distributeMinutes(minutes, parts)
        return SectionDraftPlan(
            mode = SectionDraftMode.CONTINUATION,
            parts = parts,
            partMinutes = mins,
            partTargetWords = mins.map { targetWordsFor(kind, it) },
        )
    }
    return SectionDraftPlan(
        mode = SectionDraftMode.SINGLE,
        parts = 1,
        partMinutes = listOf(minutes),
        partTargetWords = listOf(targetWordsFor(kind, minutes)),
    )
}

/** Divide [total] em [parts] sem perder o resto (12+11+11+11 para 45/4). */
fun distributeMinutes(total: Int, parts: Int): List<Int> {
    if (parts <= 0) return emptyList()
    val safeTotal = total.coerceAtLeast(0)
    val base = safeTotal / parts
    val remainder = safeTotal % parts
    return (0 until parts).map { base + if (it < remainder) 1 else 0 }
}

private fun targetWordsFor(kind: LongTextKind, minutes: Int): Int =
    if (kind == LongTextKind.SPEECH) SpeechPacing.targetWords(minutes) else 0
