package com.bettertalker.app.ui.stage

import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

/**
 * Treino MVP — preparação da sessão (spec `docs/specs/treino-mvp.md`).
 *
 * O usuário escolhe o escopo (discurso inteiro, seção ou subponto), o modo
 * (com análise de fala × só leitura) e se o cronômetro roda. A escolha é
 * lembrada por nota; o timer só começa no "Começar treino".
 */

/** O que o treino cobre nesta sessão. */
enum class TrainScopeKind { WHOLE, SECTION, SUBPOINT }

/** Escopo concreto: inteiro, uma seção ou um subponto (com a seção pai). */
data class TrainScope(
    val kind: TrainScopeKind,
    val sectionId: String? = null,
    val subPointId: String? = null,
) {
    companion object {
        val Whole = TrainScope(TrainScopeKind.WHOLE)
    }
}

/** "Com análise de fala" usa STT; "Só leitura" nunca abre o microfone. */
enum class TrainMode { ANALYSIS, READING }

/** Última preparação escolhida para a nota. */
data class TrainPrep(
    val scope: TrainScope = TrainScope.Whole,
    val mode: TrainMode = TrainMode.ANALYSIS,
    val timerEnabled: Boolean = true,
)

/** Subponto listado na escolha de trecho. */
data class TrainSubPointOption(val id: String, val text: String)

/** Seção listada na escolha de trecho (com seus subpontos, se houver). */
data class TrainSectionOption(
    val id: String,
    val title: String,
    val minutes: Int,
    val subPoints: List<TrainSubPointOption>,
)

/** Texto curto de um subponto para a lista de preparação. */
fun trainSubPointLabel(sub: SubPoint): String =
    sub.outlineText.trim().ifBlank {
        // Sem âncora: usa o desenvolvimento já escrito, se houver.
        sub.developedHtml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }.ifBlank { "Subponto ${sub.order + 1}" }

/**
 * Seções treináveis na ordem da nota. Seções sem texto e sem subpontos não
 * entram (não há o que treinar); subpontos vazios ainda aparecem pela âncora.
 */
fun trainSectionOptions(
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>>,
): List<TrainSectionOption> = sections.sortedBy { it.order }.mapNotNull { section ->
    // Só entra subponto com texto treinável (desenvolvimento ou âncora).
    val subPoints = subPointsBySection[section.id].orEmpty()
        .filter { subPointStageText(it).isNotBlank() }
        .sortedBy { it.order }
        .map { TrainSubPointOption(it.id, trainSubPointLabel(it)) }
    if (!hasTrainableSection(section, subPointsBySection[section.id].orEmpty())) {
        null
    } else {
        TrainSectionOption(
            id = section.id,
            title = section.title.ifBlank { "Seção ${section.order + 1}" },
            minutes = section.minutes,
            subPoints = subPoints,
        )
    }
}

/** A seção tem texto próprio ou ao menos um subponto treinável. */
private fun hasTrainableSection(
    section: SpeechSection,
    subPoints: List<SubPoint>,
): Boolean =
    cleanStageText(section.contentHtml).isNotBlank() ||
        subPoints.any { subPointStageText(it).isNotBlank() }

/** Preparação padrão: inteiro; análise só quando o aparelho tem STT. */
fun defaultTrainPrep(analysisAvailable: Boolean): TrainPrep = TrainPrep(
    scope = TrainScope.Whole,
    mode = if (analysisAvailable) TrainMode.ANALYSIS else TrainMode.READING,
    timerEnabled = true,
)

/**
 * Corrige uma preparação vinda da UI/preferências: ids inexistentes voltam
 * para o inteiro; sem STT, "com análise" vira "só leitura".
 */
fun sanitizeTrainPrep(
    prep: TrainPrep,
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>>,
    analysisAvailable: Boolean,
): TrainPrep {
    val scope = when (prep.scope.kind) {
        TrainScopeKind.WHOLE -> TrainScope.Whole
        TrainScopeKind.SECTION -> {
            val id = prep.scope.sectionId
            val section = sections.firstOrNull { it.id == id }
            if (section != null &&
                hasTrainableSection(section, subPointsBySection[section.id].orEmpty())
            ) {
                TrainScope(TrainScopeKind.SECTION, sectionId = id)
            } else {
                TrainScope.Whole
            }
        }
        TrainScopeKind.SUBPOINT -> {
            val subId = prep.scope.subPointId
            val pair = sections.firstNotNullOfOrNull { section ->
                subPointsBySection[section.id].orEmpty()
                    .firstOrNull { it.id == subId }
                    ?.takeIf { subPointStageText(it).isNotBlank() }
                    ?.let { section to it }
            }
            if (pair != null) {
                TrainScope(
                    kind = TrainScopeKind.SUBPOINT,
                    sectionId = pair.first.id,
                    subPointId = subId,
                )
            } else {
                TrainScope.Whole
            }
        }
    }
    return prep.copy(
        scope = scope,
        mode = if (analysisAvailable) prep.mode else TrainMode.READING,
    )
}

/** Meta de tempo do escopo: inteiro soma as seções; trecho usa a seção. */
fun trainTargetMinutes(
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>>,
    scope: TrainScope,
): Int {
    val ordered = sections.sortedBy { it.order }
    val whole = ordered.sumOf { it.minutes }
    return when (scope.kind) {
        TrainScopeKind.WHOLE -> whole
        TrainScopeKind.SECTION ->
            ordered.firstOrNull { it.id == scope.sectionId }?.minutes ?: whole
        TrainScopeKind.SUBPOINT ->
            ordered.firstOrNull { section ->
                subPointsBySection[section.id].orEmpty().any { it.id == scope.subPointId }
            }?.minutes ?: whole
    }
}

/** Rótulo do escopo para a sessão (ex.: "Discurso inteiro", título da seção). */
fun trainScopeLabel(
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>>,
    scope: TrainScope,
): String {
    val ordered = sections.sortedBy { it.order }
    return when (scope.kind) {
        TrainScopeKind.WHOLE -> "Discurso inteiro"
        TrainScopeKind.SECTION -> ordered.firstOrNull { it.id == scope.sectionId }
            ?.title?.ifBlank { "Seção" } ?: "Seção"
        TrainScopeKind.SUBPOINT -> {
            val sub = ordered.asSequence()
                .flatMap { subPointsBySection[it.id].orEmpty().asSequence() }
                .firstOrNull { it.id == scope.subPointId }
            if (sub == null) "Trecho" else "Trecho: ${trainSubPointLabel(sub)}"
        }
    }
}

/** Serializa a preparação para o DataStore (string opaca, por nota). */
fun encodeTrainPrep(prep: TrainPrep): String {
    val mode = if (prep.mode == TrainMode.ANALYSIS) "A" else "R"
    val timer = if (prep.timerEnabled) "1" else "0"
    return when (prep.scope.kind) {
        TrainScopeKind.WHOLE -> "WHOLE|$mode|$timer"
        TrainScopeKind.SECTION ->
            "SECTION|${prep.scope.sectionId.orEmpty()}|$mode|$timer"
        TrainScopeKind.SUBPOINT ->
            "SUBPOINT|${prep.scope.sectionId.orEmpty()}|" +
                "${prep.scope.subPointId.orEmpty()}|$mode|$timer"
    }
}

/**
 * Lê a preparação persistida; valor ausente/corrompido cai no padrão.
 * Ids que não existem mais são saneados para o inteiro.
 */
fun decodeTrainPrep(
    raw: String?,
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>>,
    analysisAvailable: Boolean,
): TrainPrep {
    val fallback = defaultTrainPrep(analysisAvailable)
    if (raw.isNullOrBlank()) return fallback
    val parts = raw.split('|')
    val decoded = when (parts.firstOrNull()) {
        "WHOLE" -> if (parts.size >= 3) {
            TrainPrep(
                scope = TrainScope.Whole,
                mode = if (parts[1] == "A") TrainMode.ANALYSIS else TrainMode.READING,
                timerEnabled = parts[2] == "1",
            )
        } else {
            fallback
        }
        "SECTION" -> if (parts.size >= 4) {
            TrainPrep(
                scope = TrainScope(TrainScopeKind.SECTION, sectionId = parts[1]),
                mode = if (parts[2] == "A") TrainMode.ANALYSIS else TrainMode.READING,
                timerEnabled = parts[3] == "1",
            )
        } else {
            fallback
        }
        "SUBPOINT" -> if (parts.size >= 5) {
            TrainPrep(
                scope = TrainScope(
                    kind = TrainScopeKind.SUBPOINT,
                    sectionId = parts[1],
                    subPointId = parts[2],
                ),
                mode = if (parts[3] == "A") TrainMode.ANALYSIS else TrainMode.READING,
                timerEnabled = parts[4] == "1",
            )
        } else {
            fallback
        }
        else -> fallback
    }
    return sanitizeTrainPrep(decoded, sections, subPointsBySection, analysisAvailable)
}
