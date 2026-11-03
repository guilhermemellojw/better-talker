package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.GroupStatus
import com.bettertalker.app.data.util.SourceAction
import com.bettertalker.app.data.util.SourceGroup
import com.bettertalker.app.data.util.SourceStatus
import com.bettertalker.app.data.util.TextStatus

/** Ação do botão de um grupo de fontes (separada do rótulo do badge). */
enum class SourceRowAction { NONE, FOLLOW, RESOLVE, RETRY, DOWNLOAD }

/**
 * Estado visual de uma linha de grupo no SourcesSheet. O rótulo é STATUS,
 * nunca ação: a pílula não pode dizer "Baixar" e o botão repetir "Baixar".
 */
data class SourceRowState(
    val label: String,
    val action: SourceRowAction,
    val attention: Boolean,
)

/** Decisão pura: grupo → pílula + ação. */
fun sourceRowState(group: SourceGroup): SourceRowState {
    val pending = group.pendingCount > 0
    val hasFailed = group.sources.any {
        it.status == SourceStatus.INDEX_FAILED || it.status == SourceStatus.UNSUPPORTED
    }
    val hasConferir = group.sources.any {
        it.status == SourceStatus.TEXT_NOT_FOUND || it.status == SourceStatus.EDITION_MISSING
    }
    // Ambiguidade nunca vira "Falta/Baixar": a ação certa é confirmar a edição.
    val hasAmbiguous = group.sources.any {
        it.citation.text == TextStatus.AMBIGUOUS ||
            it.citation.action == SourceAction.CONFIRM ||
            it.hint?.contains("ambígu", ignoreCase = true) == true
    }
    return when {
        group.status == GroupStatus.OK && !pending ->
            SourceRowState("Disponível", SourceRowAction.NONE, attention = false)
        pending ->
            SourceRowState("Indexando", SourceRowAction.FOLLOW, attention = false)
        hasFailed ->
            SourceRowState("Falhou", SourceRowAction.RETRY, attention = true)
        hasAmbiguous ->
            SourceRowState("Conferir", SourceRowAction.RESOLVE, attention = true)
        hasConferir && group.status != GroupStatus.MISSING ->
            SourceRowState("Conferir", SourceRowAction.RESOLVE, attention = true)
        group.status == GroupStatus.MISSING ->
            SourceRowState("Falta", SourceRowAction.DOWNLOAD, attention = true)
        else ->
            SourceRowState("Parcial", SourceRowAction.DOWNLOAD, attention = true)
    }
}
