package com.bettertalker.app.ui.stage

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

/**
 * Texto do Ensaio por tipo de nota:
 *
 * - **S34_DISCOURSE:** lê os subpontos (`developedHtml`; se vazio, `outlineText`
 *   como esboço). Nunca lê títulos de seção nem o `contentHtml` vazio.
 * - **V&M / AVULSO:** lê o `contentHtml` das seções (a fala/diálogo), sem
 *   títulos nem metadados. Para demonstrações, o `parseStageTurns` cuida dos
 *   turnos; este texto é o fallback.
 *
 * Puro/testável.
 */
fun buildStageContent(
    discourseType: DiscourseType,
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>>,
): String {
    val ordered = sections.sortedBy { it.order }
    return if (discourseType == DiscourseType.S34_DISCOURSE) {
        ordered.flatMap { section ->
            val subs = subPointsBySection[section.id].orEmpty().sortedBy { it.order }
            if (subs.isEmpty()) {
                listOf(cleanStageText(section.contentHtml)).filter { it.isNotBlank() }
            } else {
                subs.map { sub ->
                    val developed = cleanStageText(sub.developedHtml)
                    developed.ifBlank { sub.outlineText.trim() }
                }.filter { it.isNotBlank() }
            }
        }.joinToString("\n\n")
    } else {
        ordered.mapNotNull { section ->
            cleanStageText(section.contentHtml).takeIf { it.isNotBlank() }
        }.joinToString("\n\n")
    }
}
