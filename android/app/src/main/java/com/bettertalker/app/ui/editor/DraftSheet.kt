package com.bettertalker.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Fase 3.5e.3 — sheet de preview do rascunho gerado pelo Copilot.
 *
 * Padrão: ModalBottomSheet (AttachSheet/ToolsSheet do chat). Estados:
 * Loading (escrevendo), Error (tentar de novo/fechar), Ready (preview +
 * fidelity report + Aceitar/Descartar). Nunca bloqueia o aceite — o
 * relatório informa, o usuário decide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftSheet(
    state: DraftUiState,
    sections: List<SectionUiState>,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                DraftUiState.Idle -> Unit

                is DraftUiState.Loading -> {
                    Text("Rascunho do Copilot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        alvoLabel(state.target, sections),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Copilot está escrevendo…", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                }

                is DraftUiState.Error -> {
                    Text("Rascunho do Copilot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        alvoLabel(state.target, sections),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetry) { Text("Tentar novamente") }
                        TextButton(onClick = onDismiss) { Text("Fechar") }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                is DraftUiState.Ready -> {
                    Text("Rascunho do Copilot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        alvoLabel(state.target, sections),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )

                    // Fidelity report (informativo — nunca bloqueia o aceite).
                    val v = state.draft.validation
                    if (v.ok) {
                        Text(
                            "✓ Fiel ao dossiê",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        FidelityLine("Referências bíblicas inventadas:", v.inventedBibleRefs)
                        FidelityLine("Publicações inventadas:", v.inventedPublicationRefs)
                        FidelityLine("Fontes fora do dossiê:", v.usedSourcesOutsideDossier)
                        FidelityLine("Referências não resolvidas:", v.unresolvedRefsCited)
                    }

                    Spacer(Modifier.height(4.dp))
                    // Aviso de truncamento (informativo — nunca bloqueia o aceite).
                    if (state.draft.possiblyTruncated) {
                        Text(
                            "⚠ Resposta encurtada — o modelo parou antes do fim.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stripHtml(state.draft.textHtml),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Descartar") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onAccept) { Text("Aceitar") }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                is DraftUiState.Accepted -> {
                    Text("Rascunho aplicado", style = MaterialTheme.typography.titleMedium)
                    Text(
                        alvoLabel(state.target, sections),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Fechar") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onUndo) { Text("Desfazer") }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** Linha do relatório de fidelidade (só aparece se houver itens). */
@Composable
private fun FidelityLine(label: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(
        "$label ${items.joinToString(", ")}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
