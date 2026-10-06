package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import kotlin.math.roundToInt

/**
 * Helpers puros de contagem de palavras para o editor multi-seção (3.2.5c).
 * Testáveis sem Compose/Room.
 */

/** Conta palavras de um texto que pode conter HTML (tags viram separador). */
fun countWords(text: String): Int =
    text.replace(Regex("<[^>]+>"), " ")
        .split(Regex("\\s+"))
        .count { it.any(Char::isLetterOrDigit) }

/**
 * Conta palavras das seções: BODY usa o mini discurso (`contentHtml`, a
 * verdade do tópico) quando preenchido; senão, os sub-pontos (legado).
 * Espelha a regra do `SectionsController.aggregateHtml` — nunca soma os
 * dois (o desenvolvimento legado fica invisível quando há mini discurso).
 */
fun countSectionWords(sections: List<SectionUiState>): Int = sections.sumOf { s ->
    if (s.section.role == SectionRole.BODY) {
        if (s.section.contentHtml.isNotBlank()) countWords(s.section.contentHtml)
        else s.subPoints.sumOf { countWords(it.developedHtml) }
    } else {
        countWords(s.section.contentHtml)
    }
}

/** Ritmo de fala médio (palavras por minuto) para a estimativa do mini discurso. */
const val WORDS_PER_MINUTE = 130

/**
 * Estimativa de duração do texto em minutos (130 palavras/min, arredondado).
 * Vazio = 0; qualquer texto não vazio conta ao menos 1 min. Puro/testável.
 */
fun estimateMinutes(text: String): Int {
    val words = countWords(text)
    if (words <= 0) return 0
    return maxOf(1, (words / WORDS_PER_MINUTE.toDouble()).roundToInt())
}

/** Situação do mini discurso em relação à meta de minutos do tópico. */
enum class MiniSpeechTimeStatus { NEUTRAL, LONG, SHORT }

/** Diferença de até 1 min é neutra; ≥ 2 min para cima = longo; para baixo = curto. */
fun miniSpeechTimeStatus(estimate: Int, target: Int): MiniSpeechTimeStatus = when {
    estimate - target >= 2 -> MiniSpeechTimeStatus.LONG
    target - estimate >= 2 -> MiniSpeechTimeStatus.SHORT
    else -> MiniSpeechTimeStatus.NEUTRAL
}

/** "≈ N min" (neutro) ou "≈ N min (meta: M)" quando desvia da meta. */
fun miniSpeechTimeLabel(estimate: Int, target: Int): String =
    if (miniSpeechTimeStatus(estimate, target) == MiniSpeechTimeStatus.NEUTRAL) "≈ $estimate min"
    else "≈ $estimate min (meta: $target)"
