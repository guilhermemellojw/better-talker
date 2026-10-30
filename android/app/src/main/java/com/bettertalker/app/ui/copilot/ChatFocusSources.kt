package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.FocusCounts
import com.bettertalker.app.data.copilot.FocusDestination
import com.bettertalker.app.data.copilot.FocusSources
import com.bettertalker.app.data.copilot.FocusStatus
import com.bettertalker.app.data.copilot.focusStatusLabel

/**
 * Resumo de uma linha para o cabeçalho de "Fontes e apoio". Puro/testável.
 *
 * Ex.: "5 do ponto · 3 no prompt · 1 sem texto".
 */
fun focusSourcesSummary(counts: FocusCounts): String {
    if (counts.point == 0) {
        return if (counts.turnRefs > 0) "${counts.turnRefs} do turno" else "0 refs"
    }
    return buildList {
        add("${counts.point} do ponto")
        if (counts.content > 0) add("${counts.content} no prompt")
        if (counts.dossier > 0) add("${counts.dossier} no dossiê")
        if (counts.noText > 0) add("${counts.noText} sem texto")
        if (counts.orientation > 0) add("${counts.orientation} orientação")
        if (counts.budget > 0) add("${counts.budget} fora do orçamento")
    }.joinToString(" · ")
}

/**
 * Conteúdo informativo do ponto atual, dentro do disclosure "Fontes e apoio".
 * Sem Card, sem segundo controle de expandir: a seção só aparece quando o
 * disclosure já está aberto.
 */
@Composable
fun FocusSourcesSection(
    sources: FocusSources,
    busy: Boolean,
    onReanalyze: () -> Unit,
) {
    var showAll by remember(sources) { mutableStateOf(false) }
    var showText by remember(sources) { mutableStateOf(false) }
    var verifyKey by remember(sources) { mutableStateOf<String?>(null) }
    val counts = sources.counts

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            "Deste ponto",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            focusSourcesSummary(counts),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (counts.orientation > 0) {
            val labels = sources.pointEntries
                .filter { it.isOrientation }
                .mapNotNull { it.publicationKey }
                .distinct()
                .joinToString("/")
                .ifBlank { "be/th" }
            Text(
                "${counts.orientation} orientação ($labels) no inventário",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (counts.noText > 0) {
            Text(
                "${counts.noText} sem texto",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (counts.budget > 0) {
            Text(
                "${counts.budget} fora do orçamento",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        val point = sources.pointEntries
        if (point.isEmpty()) {
            Text(
                "Nenhuma ref do ponto; veja o turno abaixo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val shown = if (showAll) point.take(40) else point.take(5)
            shown.forEach { source ->
                val tag = when {
                    source.isOrientation -> "orientação"
                    source.extra -> "extra do turno"
                    else -> focusDestinationLabel(source.destination)
                }
                Text(
                    "• ${source.reference} — ${focusStatusLabel(source.status)} ($tag)",
                    style = MaterialTheme.typography.bodySmall,
                    color = focusStatusColor(source.status),
                )
                source.omissionReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Text(
                        "  $reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (source.text.isNotBlank()) {
                    Text(
                        source.text.take(240),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            verifyKey = if (verifyKey == source.key) null else source.key
                        },
                    ) {
                        Text(if (verifyKey == source.key) "Ocultar" else "Verificar")
                    }
                    if (verifyKey == source.key) {
                        Text(source.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (point.size > shown.size) {
                Text(
                    "… ${point.size - shown.size} outras refs no inventário",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(
                onClick = { showAll = !showAll },
                enabled = !busy,
            ) { Text(if (showAll) "Ocultar fontes" else "Ver todas") }
            TextButton(
                onClick = { showText = !showText },
                enabled = !busy,
            ) { Text("Texto usado") }
            TextButton(onClick = onReanalyze, enabled = !busy) { Text("Reanalisar") }
        }

        if (showText) {
            val used = sources.promptSources
            if (used.isEmpty()) {
                Text(
                    "Nenhum texto novo: as fontes do ponto já estão no dossiê ou no pack.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                used.forEach { source ->
                    Text(
                        source.reference,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(source.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun focusStatusColor(status: FocusStatus): Color = when (status) {
    FocusStatus.RESOLVED -> MaterialTheme.colorScheme.secondary
    FocusStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

internal fun focusDestinationLabel(destination: FocusDestination): String = when (destination) {
    FocusDestination.FOCUS_BLOCK -> "no bloco"
    FocusDestination.DOSSIER -> "no dossiê"
    FocusDestination.PACK -> "no pack"
    FocusDestination.QUESTION -> "na pergunta"
    FocusDestination.INVENTORY -> "inventário"
    FocusDestination.OMITTED -> "omitida"
}
