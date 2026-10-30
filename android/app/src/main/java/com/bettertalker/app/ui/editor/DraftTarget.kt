package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.planning.SectionDraft
import com.bettertalker.app.domain.speech.SectionRole

/**
 * Alvo explícito de um draft gerado pelo Copilot no editor.
 *
 * Fase 3.5e.3: a ação "Gerar com Copilot" parte do menu ⋮ do sub-ponto
 * ou do menu ⋮ da seção (INTRO/CONCLUSION) — nunca depende da seleção
 * de texto atual (`selection`), que muda a cada 200ms.
 */
sealed interface DraftTarget {
    val sectionId: String

    /** Draft para um sub-ponto de uma seção BODY. */
    data class SubPoint(override val sectionId: String, val subPointId: String) : DraftTarget

    /** Draft para a seção inteira (INTRO/CONCLUSION, ou BODY sem sub-pontos). */
    data class Section(override val sectionId: String) : DraftTarget
}

/** Estado da geração de draft no editor (padrão ChatRunState). */
sealed interface DraftUiState {
    data object Idle : DraftUiState

    /** [queueRemaining] = partes ainda na fila do "texto total" (0 = avulso). */
    data class Loading(
        val target: DraftTarget,
        val queueRemaining: Int = 0,
        /** M3: parte interna atual (1-based) e total. */
        val partIndex: Int = 1,
        val partCount: Int = 1,
        val targetWords: Int = 0,
        val wordsSoFar: Int = 0,
        /** "Continuar geração" (não é uma parte do plano inicial). */
        val continuation: Boolean = false,
    ) : DraftUiState

    data class Ready(
        val target: DraftTarget,
        val draft: SectionDraft,
        /** Meta total da seção (0 = demo/desconhecida). */
        val targetWords: Int = 0,
        val words: Int = 0,
        /** Falha ao continuar um parcial; o draft atual é preservado. */
        val notice: String? = null,
    ) : DraftUiState
    data class Error(val target: DraftTarget, val message: String) : DraftUiState

    /**
     * Rascunho foi aplicado. Guarda o HTML anterior ([previousHtml]) para
     * undo e o aplicado ([appliedHtml]) como registro do que foi aplicado.
     * [queueRemaining] > 0 mostra o botão "Próximo" no sheet.
     */
    data class Accepted(
        val target: DraftTarget,
        val previousHtml: String,
        val appliedHtml: String,
        val queueRemaining: Int = 0,
    ) : DraftUiState
}

/** Seção longa (20+ min) deve ser dividida por sub-ponto para não truncar. */
const val LONG_SECTION_MINUTES: Int = 20

/**
 * Alvo efetivo do draft: seções de 20+ min com sub-pontos são geradas por
 * sub-ponto (o primeiro), preservando o tempo da seção sem estourar o teto
 * de saída. Puro/testável.
 */
fun preferredDraftTarget(
    target: DraftTarget,
    sections: List<SectionUiState>,
): DraftTarget {
    if (target !is DraftTarget.Section) return target
    val state = sections.firstOrNull { it.section.id == target.sectionId } ?: return target
    if (state.section.role != SectionRole.BODY ||
        state.section.minutes < LONG_SECTION_MINUTES ||
        state.subPoints.isEmpty()
    ) {
        return target
    }
    val first = state.subPoints.minByOrNull { it.order } ?: return target
    return DraftTarget.SubPoint(target.sectionId, first.id)
}

/**
 * Fila do "texto total": Intro/Conclusão por seção; BODY de 20+ min por
 * sub-ponto; demais BODYs por seção. A ordem segue a ordem da nota.
 * Puro/testável.
 */
fun totalTextTargets(sections: List<SectionUiState>): List<DraftTarget> =
    sections.sortedBy { it.section.order }.flatMap { state ->
        val section = state.section
        if (section.role == SectionRole.BODY &&
            section.minutes >= LONG_SECTION_MINUTES &&
            state.subPoints.isNotEmpty()
        ) {
            state.subPoints.sortedBy { it.order }.map {
                DraftTarget.SubPoint(section.id, it.id)
            }
        } else {
            listOf(DraftTarget.Section(section.id))
        }
    }

/**
 * Rótulo PT-BR do alvo do draft (para a sheet). Puro/testável.
 *
 * Deriva dos índices em [sections]; se o alvo não estiver na lista,
 * omite o índice ("Seção"/"Sub-ponto").
 */
fun alvoLabel(target: DraftTarget, sections: List<SectionUiState>): String {
    val sectionIdx = sections.indexOfFirst { it.section.id == target.sectionId }
    return when (target) {
        is DraftTarget.Section -> {
            val role = if (sectionIdx >= 0) sections[sectionIdx].section.role else null
            when {
                // F2.3: o BODY é um tópico (unidade do mini discurso).
                role == SectionRole.BODY -> "Tópico ${sectionIdx + 1}"
                sectionIdx >= 0 -> "Seção ${sectionIdx + 1}"
                else -> "Seção"
            }
        }

        is DraftTarget.SubPoint -> {
            val spIdx = if (sectionIdx >= 0) {
                sections[sectionIdx].subPoints.indexOfFirst { it.id == target.subPointId }
            } else -1
            when {
                sectionIdx >= 0 && spIdx >= 0 -> "Seção ${sectionIdx + 1} · Sub-ponto ${spIdx + 1}"
                sectionIdx >= 0 -> "Seção ${sectionIdx + 1} · Sub-ponto"
                spIdx >= 0 -> "Sub-ponto ${spIdx + 1}"
                else -> "Sub-ponto"
            }
        }
    }
}

/**
 * M3 — o parcial é curto/cortado e o sheet deve oferecer "Continuar geração".
 * 80% da meta (mesma régua do card). Puro/testável.
 */
internal fun draftNeedsContinuation(
    possiblyTruncated: Boolean,
    words: Int,
    targetWords: Int,
): Boolean {
    if (possiblyTruncated) return true
    if (targetWords <= 0) return false
    return words < targetWords * 80 / 100
}

/** "Parte 1 de 2 · ~2.990 palavras" (+ escritas quando houver). */
internal fun draftPartProgressLabel(
    partIndex: Int,
    partCount: Int,
    targetWords: Int,
    wordsSoFar: Int,
): String? {
    if (partCount <= 1) return null
    val base = "Parte $partIndex de $partCount" +
        if (targetWords > 0) " · ~${formatDraftWords(targetWords)} palavras" else ""
    return if (wordsSoFar > 0) {
        "$base · ${formatDraftWords(wordsSoFar)} escritas"
    } else {
        base
    }
}

/** "Continuação · ~1.455 palavras · 4.395 escritas". */
internal fun draftContinuationLabel(targetWords: Int, wordsSoFar: Int): String =
    buildString {
        append("Continuação")
        if (targetWords > 0) {
            append(" · ~").append(formatDraftWords(targetWords)).append(" palavras")
        }
        if (wordsSoFar > 0) {
            append(" · ").append(formatDraftWords(wordsSoFar)).append(" escritas")
        }
    }

/** "5.862 de ~5.850 palavras" (ou só "5.862 palavras" sem meta). */
internal fun draftWordsLabel(words: Int, targetWords: Int): String =
    if (targetWords > 0) {
        "${formatDraftWords(words)} de ~${formatDraftWords(targetWords)} palavras"
    } else {
        "${formatDraftWords(words)} palavras"
    }

/** "Aceitar" quando completo; "Aceitar mesmo assim" quando curto/cortado. */
internal fun draftAcceptLabel(needsContinuation: Boolean): String =
    if (needsContinuation) "Aceitar mesmo assim" else "Aceitar"

/** Palavras com separador PT-BR ("5.850"), sem Locale do aparelho. */
internal fun formatDraftWords(n: Int): String {
    val s = n.coerceAtLeast(0).toString()
    val sb = StringBuilder()
    s.forEachIndexed { i, c ->
        if (i > 0 && (s.length - i) % 3 == 0) sb.append('.')
        sb.append(c)
    }
    return sb.toString()
}

/**
 * Rótulo legível do role, em caixa alta (INTRODUÇÃO / DESENVOLVIMENTO /
 * CONCLUSÃO). Usado no chip da aba, no dropdown de troca, no header do
 * chat ("Conversando sobre") e onde mais for preciso — fonte única para
 * não divergir.
 *
 * Pura, testável.
 */
internal fun roleLabel(role: SectionRole): String = when (role) {
    SectionRole.INTRO -> "INTRODUÇÃO"
    SectionRole.BODY -> "DESENVOLVIMENTO"
    SectionRole.CONCLUSION -> "CONCLUSÃO"
}

/**
 * Overload para campos persistidos (a entidade guarda `role` como String).
 * Nome desconhecido cai em DESENVOLVIMENTO (nunca quebra leitura).
 */
internal fun roleLabel(roleName: String): String = when (roleName) {
    SectionRole.INTRO.name -> "INTRODUÇÃO"
    SectionRole.CONCLUSION.name -> "CONCLUSÃO"
    else -> "DESENVOLVIMENTO"
}

/**
 * Remove tags HTML e colapsa espaços (mesma regex do `DossierBuilder`
 * privado, replicada aqui para o preview da sheet ser puro/testável).
 *
 * Não usar `Html.fromHtml` — JVM puro (testes unitários).
 */
fun stripHtml(html: String): String =
    html.replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Parseia o `activeEditorKey` para DraftTarget.
 * - "section-{id}" → Section(id)
 * - "subpoint-{id}" → SubPoint(sectionId, id) — precisa resolver o
 *   sectionId do sub-ponto
 * - null / desconhecido → null (chat genérico)
 *
 * `resolveSectionFor` é injetado porque o helper não tem acesso à
 * StateFlow de seções.
 *
 * Pura, testável.
 */
internal fun targetFromEditorKey(
    key: String?,
    resolveSectionFor: (subPointId: String) -> String?,
): DraftTarget? {
    if (key == null) return null
    if (key.startsWith("section-")) {
        val id = key.removePrefix("section-")
        return if (id.isBlank()) null else DraftTarget.Section(id)
    }
    if (key.startsWith("subpoint-")) {
        val id = key.removePrefix("subpoint-")
        if (id.isBlank()) return null
        val sectionId = resolveSectionFor(id) ?: return null
        return DraftTarget.SubPoint(sectionId, id)
    }
    return null
}
