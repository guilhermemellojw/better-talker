package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole

/**
 * Helpers puros de contagem de palavras para o editor multi-seção (3.2.5c).
 * Testáveis sem Compose/Room.
 */

/** Conta palavras de um texto que pode conter HTML (tags viram separador). */
fun countWords(text: String): Int =
    text.replace(Regex("<[^>]+>"), " ")
        .split(Regex("\\s+"))
        .count { it.any(Char::isLetterOrDigit) }

/** Conta palavras das seções: BODY soma os sub-pontos; as demais, contentHtml. */
fun countSectionWords(sections: List<SectionUiState>): Int = sections.sumOf { s ->
    if (s.section.role == SectionRole.BODY) {
        s.subPoints.sumOf { countWords(it.developedHtml) }
    } else {
        countWords(s.section.contentHtml)
    }
}
