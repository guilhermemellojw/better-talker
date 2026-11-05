package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.GroupStatus
import com.bettertalker.app.data.util.SourceAction
import com.bettertalker.app.data.util.SourceGroup
import com.bettertalker.app.data.util.SourceStatus
import com.bettertalker.app.data.util.TextStatus

/** Ação do botão de um grupo de fontes (separada do rótulo do badge). */
enum class SourceRowAction { NONE, FOLLOW, RESOLVE, RETRY, OPEN, DOWNLOAD }

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
    val hasMissingEdition = group.sources.any {
        it.status == SourceStatus.EDITION_MISSING
    }
    // Edição pronta com texto parcial/ausente: o arquivo está no aparelho.
    // A ação certa é conferir/reindexar na Biblioteca, nunca baixar de novo.
    val hasPartialText = group.sources.any {
        it.citation.edition == EditionStatus.READY &&
            (it.citation.text == TextStatus.PARTIAL ||
                it.citation.action == SourceAction.OPEN)
    }
    val hasMissingText = group.sources.any {
        it.citation.edition == EditionStatus.READY &&
            (it.citation.text == TextStatus.NOT_FOUND ||
                it.citation.action == SourceAction.REINDEX)
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
        // Edição ausente continua sendo download, mesmo com texto faltando.
        hasMissingEdition ->
            SourceRowState("Falta", SourceRowAction.DOWNLOAD, attention = true)
        // Texto ausente com edição pronta: conferir/reindexar.
        hasMissingText ->
            SourceRowState("Conferir", SourceRowAction.OPEN, attention = true)
        // Texto parcial: a edição está no aparelho; abrir para conferir.
        hasPartialText ->
            SourceRowState("Parcial", SourceRowAction.OPEN, attention = true)
        group.status == GroupStatus.MISSING ->
            SourceRowState("Falta", SourceRowAction.DOWNLOAD, attention = true)
        else ->
            SourceRowState("Parcial", SourceRowAction.OPEN, attention = true)
    }
}

/**
 * Primeira URL de download disponível em QUALQUER fonte do grupo. Evita o
 * fallback silencioso para a Biblioteca quando a primeira fonte não tem URL.
 */
fun downloadUrlFor(group: SourceGroup): String? =
    group.sources.firstNotNullOfOrNull { source ->
        source.downloadUrl?.takeIf { it.isNotBlank() }
            ?: source.refStatus?.downloadUrl?.takeIf { it.isNotBlank() }
    }
