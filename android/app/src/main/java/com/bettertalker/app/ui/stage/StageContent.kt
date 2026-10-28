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
                subs.map { subPointStageText(it) }.filter { it.isNotBlank() }
            }
        }.joinToString("\n\n")
    } else {
        ordered.mapNotNull { section ->
            cleanStageText(section.contentHtml).takeIf { it.isNotBlank() }
        }.joinToString("\n\n")
    }
}

/** Texto de um subponto: desenvolvimento quando existe; senão a âncora. */
fun subPointStageText(sub: SubPoint): String =
    cleanStageText(sub.developedHtml).ifBlank { sub.outlineText.trim() }

/**
 * Texto do Treino limitado ao escopo escolhido (spec Treino MVP):
 *
 * - **WHOLE:** mesma travessia de [buildStageContent].
 * - **SECTION:** só a seção escolhida, com seus subpontos (S-34) ou
 *   `contentHtml` (V&M/avulso).
 * - **SUBPOINT:** só o desenvolvimento/âncora do subponto.
 *
 * Escopo inválido cai no discurso inteiro (nunca abre sessão vazia por id
 * obsoleto). Puro/testável.
 */
fun buildStageContentForScope(
    discourseType: DiscourseType,
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>>,
    scope: TrainScope,
): String {
    val ordered = sections.sortedBy { it.order }
    return when (scope.kind) {
        TrainScopeKind.WHOLE -> buildStageContent(discourseType, ordered, subPointsBySection)
        TrainScopeKind.SECTION -> {
            val section = ordered.firstOrNull { it.id == scope.sectionId }
                ?: return buildStageContent(discourseType, ordered, subPointsBySection)
            buildStageContent(discourseType, listOf(section), subPointsBySection)
        }
        TrainScopeKind.SUBPOINT -> {
            val sub = ordered.asSequence()
                .flatMap { subPointsBySection[it.id].orEmpty().asSequence() }
                .firstOrNull { it.id == scope.subPointId }
                ?: return buildStageContent(discourseType, ordered, subPointsBySection)
            subPointStageText(sub)
        }
    }
}
