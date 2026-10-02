package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.planning.SectionDraft

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
    data class Loading(val target: DraftTarget) : DraftUiState
    data class Ready(val target: DraftTarget, val draft: SectionDraft) : DraftUiState
    data class Error(val target: DraftTarget, val message: String) : DraftUiState

    /**
     * Rascunho foi aplicado. Guarda o HTML anterior ([previousHtml]) para
     * undo e o aplicado ([appliedHtml]) como registro do que foi aplicado.
     */
    data class Accepted(
        val target: DraftTarget,
        val previousHtml: String,
        val appliedHtml: String,
    ) : DraftUiState
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
        is DraftTarget.Section ->
            if (sectionIdx >= 0) "Seção ${sectionIdx + 1}" else "Seção"

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
