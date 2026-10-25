package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole

/** Uma seção sugerida pelo "Criar esboço do zero". */
data class SuggestedSection(
    val role: SectionRole,
    val title: String,
    val minutes: Int,
)

/**
 * Estrutura inicial sugerida para uma nota criada do zero.
 *
 * @param type S34_DISCOURSE (Intro + N pontos + Conclusão) ou FREE_DEMO
 *   (1 BODY de diálogo).
 * @param totalMinutes duração total; deve ser igual à soma das seções.
 * @param requestedPoints pontos pedidos pelo usuário (0 em FREE_DEMO).
 * @param effectivePoints pontos que couberam na duração (pode ser menor).
 * @param adjusted true quando [effectivePoints] != [requestedPoints] (ou o
 *   pedido foi limitado a 1..5).
 */
data class SuggestedStructure(
    val type: DiscourseType,
    val totalMinutes: Int,
    val sections: List<SuggestedSection>,
    val requestedPoints: Int,
    val effectivePoints: Int,
    val adjusted: Boolean,
)

/**
 * Sugere a estrutura de uma nota criada do zero. Puro/testável.
 *
 * Discurso público: Intro 1 + N pontos + Conclusão 1. O restante é dividido
 * em `base + remainder nos primeiros` (10 min/3 → 3,3,2). Se N não couber,
 * reduz N (nunca deixa seção com menos de 1 min).
 *
 * Demonstração livre: 1 BODY "Demonstração" com a duração total.
 *
 * Retorna null para tipo/duração inválidos. O dialog usa null para desabilitar
 * "Criar nota"; a UI mostra a mensagem específica.
 */
fun suggestStructure(
    totalMinutes: Int,
    type: DiscourseType,
    requestedPoints: Int = 3,
): SuggestedStructure? = when (type) {
    DiscourseType.S34_DISCOURSE -> suggestSpeech(totalMinutes, requestedPoints)
    DiscourseType.FREE_DEMO -> suggestFreeDemo(totalMinutes)
    else -> null
}

private fun suggestSpeech(totalMinutes: Int, requestedPoints: Int): SuggestedStructure? {
    if (totalMinutes !in 3..45) return null
    val remaining = totalMinutes - 2 // intro + conclusão
    if (remaining < 1) return null
    val requested = requestedPoints.coerceIn(1, 5)
    val effective = minOf(requested, remaining)
    if (effective < 1) return null
    val base = remaining / effective
    val remainder = remaining % effective
    val sections = buildList {
        add(SuggestedSection(SectionRole.INTRO, "Introdução", 1))
        repeat(effective) { i ->
            add(
                SuggestedSection(
                    role = SectionRole.BODY,
                    title = "Ponto ${i + 1}",
                    minutes = base + if (i < remainder) 1 else 0,
                )
            )
        }
        add(SuggestedSection(SectionRole.CONCLUSION, "Conclusão", 1))
    }
    return SuggestedStructure(
        type = DiscourseType.S34_DISCOURSE,
        totalMinutes = totalMinutes,
        sections = sections,
        requestedPoints = requested,
        effectivePoints = effective,
        adjusted = effective != requested,
    )
}

private fun suggestFreeDemo(totalMinutes: Int): SuggestedStructure? {
    if (totalMinutes !in 2..45) return null
    return SuggestedStructure(
        type = DiscourseType.FREE_DEMO,
        totalMinutes = totalMinutes,
        sections = listOf(
            SuggestedSection(SectionRole.BODY, "Demonstração", totalMinutes),
        ),
        requestedPoints = 0,
        effectivePoints = 0,
        adjusted = false,
    )
}
