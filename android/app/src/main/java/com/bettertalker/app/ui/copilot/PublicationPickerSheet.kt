package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.db.AttachmentEntity

/**
 * T2 (Anexar do acervo) — rótulo humano do status da publicação. Puro/testável.
 */
internal fun publicationStatusLabel(status: String): String = when (status) {
    "ready" -> "Indexado"
    "failed" -> "Falhou"
    "downloading" -> "Baixando…"
    else -> "Indexando…"
}

/**
 * T2 — filtro por nome de arquivo ou símbolo (busca do sheet). Puro/testável.
 */
internal fun filterPublications(
    query: String,
    items: List<AttachmentEntity>
): List<AttachmentEntity> {
    val q = query.trim().lowercase()
    if (q.isBlank()) return items
    return items.filter {
        it.fileName.lowercase().contains(q) || it.symbol?.lowercase()?.contains(q) == true
    }
}

/**
 * T2 — sheet do "+" para vincular uma publicação do acervo à nota atual.
 * Reaproveita o fluxo de vínculo da Biblioteca (linkToNote).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicationPickerSheet(vm: CopilotViewModel, onDismiss: () -> Unit) {
    val items by vm.publications.collectAsState(initial = emptyList())
    val noteId = vm.currentNoteId
    var query by remember { mutableStateOf("") }
    var pendingRelink by remember { mutableStateOf<AttachmentEntity?>(null) }
    val filtered = remember(query, items) { filterPublications(query, items) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Anexar do acervo", style = MaterialTheme.typography.titleMedium)
            Text(
                "Vincule uma publicação importada para eu poder citá-la nesta conversa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar por nome ou símbolo…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Text(
                    "Nenhuma publicação no acervo. Use “Baixar publicações” ou importe um arquivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                ) {
                    items(filtered, key = { it.id }) { a ->
                        val linkedHere = noteId != null && a.noteId == noteId
                        val linkedElsewhere = a.noteId != null && a.noteId != noteId
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.fileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1
                                    )
                                    Text(
                                        publicationStatusLabel(a.status) +
                                            if (linkedHere) " • Nesta nota" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (linkedHere) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondary
                                    )
                                }
                                when {
                                    linkedHere -> TextButton(
                                        onClick = { vm.linkPublication(a.id, null) }
                                    ) { Text("Desvincular") }
                                    a.status == "failed" -> TextButton(
                                        onClick = { vm.retryRegisterPublication(a.id) }
                                    ) { Text("Tentar de novo") }
                                    a.status == "ready" && noteId != null -> TextButton(onClick = {
                                        if (linkedElsewhere) {
                                            pendingRelink = a
                                        } else {
                                            vm.linkPublication(a.id, noteId)
                                            onDismiss()
                                        }
                                    }) { Text("Vincular") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingRelink?.let { a ->
        AlertDialog(
            onDismissRequest = { pendingRelink = null },
            title = { Text("Mover para esta nota?") },
            text = {
                Text("Esta publicação já está vinculada a outra nota. Mover para a nota atual?")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.linkPublication(a.id, noteId)
                    pendingRelink = null
                    onDismiss()
                }) { Text("Mover") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRelink = null }) { Text("Cancelar") }
            }
        )
    }
}
