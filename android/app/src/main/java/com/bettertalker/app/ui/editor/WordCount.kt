package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.planning.SpeechPacing
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

/**
 * Contagem do esboço ainda não desenvolvido: só subpontos sem `developedHtml`,
 * e só em BODY cujo mini discurso está vazio (se o BODY já tem mini discurso,
 * os subpontos são legado e não entram na conta). Nunca soma o mesmo campo
 * duas vezes. Puro/testável.
 */
fun countOutlineWords(sections: List<SectionUiState>): Int = sections.sumOf { s ->
    if (s.section.role != SectionRole.BODY || s.section.contentHtml.isNotBlank()) {
        0
    } else {
        s.subPoints
            .filter { it.developedHtml.isBlank() }
            .sumOf { countWords(it.outlineText) }
    }
}

/**
 * Contador do editor com dois estados separados:
 * - [developed]: texto desenvolvido (contentHtml/developedHtml) — mesma
 *   semântica do [countSectionWords];
 * - [outline]: esboço ainda não desenvolvido (outlineText).
 *
 * A UI escolhe o rótulo; o "0 desenvolvidas · N no esboço" é honesto e a dica
 * "desenvolva este ponto" só aparece quando há esboço e nada desenvolvido.
 */
data class EditorWordCount(
    val developed: Int,
    val outline: Int,
) {
    val needsDevelopmentHint: Boolean get() = developed == 0 && outline > 0

    val label: String
        get() = when {
            developed > 0 && outline > 0 ->
                "$developed palavras desenvolvidas · $outline no esboço"
            developed > 0 -> "$developed palavras"
            outline > 0 -> "0 desenvolvidas · $outline no esboço"
            else -> "0 palavras"
        }
}

/**
 * Contador do editor. Sem seções, mantém o fallback no `mdText` (nota livre)
 * como texto desenvolvido. Puro/testável.
 */
fun editorWordCount(
    sections: List<SectionUiState>,
    mdText: String,
): EditorWordCount =
    if (sections.isEmpty()) {
        EditorWordCount(developed = countWords(mdText), outline = 0)
    } else {
        EditorWordCount(
            developed = countSectionWords(sections),
            outline = countOutlineWords(sections),
        )
    }

/**
 * Estimativa de duração do texto em minutos (ritmo único de [SpeechPacing],
 * arredondado). Vazio = 0; qualquer texto não vazio conta ao menos 1 min.
 * Puro/testável.
 */
fun estimateMinutes(text: String): Int {
    val words = countWords(text)
    if (words <= 0) return 0
    return maxOf(1, (words / SpeechPacing.WORDS_PER_MINUTE.toDouble()).roundToInt())
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
