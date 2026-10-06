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
