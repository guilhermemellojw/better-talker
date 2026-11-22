package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val MEMORY_LIMITATION =
    "As decisões salvas aparecem aqui. O Mentor ainda pode considerar a " +
        "abordagem registrada no tópico e o histórico da conversa."

/** 1C.2.2A — bottom sheet somente leitura com as decisões persistidas da nota. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DecisionMemorySheet(
    state: DecisionMemoryUiState,
    noteTitle: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onRemoveDecision: (String) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        DecisionMemoryContent(
            state = state,
            noteTitle = noteTitle,
            onClose = onDismiss,
            onRetry = onRetry,
            onRemoveDecision = onRemoveDecision,
        )
    }
}

@Composable
fun DecisionMemoryContent(
    state: DecisionMemoryUiState,
    noteTitle: String,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onRemoveDecision: (String) -> Unit = {},
) {
    var selected by remember { mutableStateOf<DecisionMemoryItemUi?>(null) }
    var confirmRemove by remember { mutableStateOf<DecisionMemoryItemUi?>(null) }
    val error = state.error

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Memória do Mentor", style = MaterialTheme.typography.titleLarge)
                if (noteTitle.isNotBlank()) {
                    Text(
                        noteTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Fechar memória")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            MEMORY_LIMITATION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.isLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text(
                    "Carregando decisões…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }

            state.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                TextButton(onClick = onRetry) { Text("Tentar novamente") }
            }

            state.isEmpty -> Text(
                "Ainda não há decisões salvas para este discurso.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.active.isNotEmpty()) {
                    item(key = "header-active") {
                        MemorySectionHeader("Ativas", state.active.size)
                    }
                    items(state.active, key = { it.id }) { item ->
                        DecisionMemoryRow(item, onClick = { selected = item })
                    }
                }
                if (state.attention.isNotEmpty()) {
                    item(key = "header-attention") {
                        MemorySectionHeader("Precisam de atenção", state.attention.size)
                    }
                    items(state.attention, key = { it.id }) { item ->
                        DecisionMemoryRow(item, onClick = { selected = item })
                    }
                }
                if (state.history.isNotEmpty()) {
                    item(key = "header-history") {
                        MemorySectionHeader("Histórico", state.history.size)
                    }
                    items(state.history, key = { it.id }) { item ->
                        DecisionMemoryRow(item, onClick = { selected = item })
                    }
                }
                item(key = "footer-space") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    selected?.let { item ->
        DecisionMemoryDetailDialog(
            item = item,
            onDismiss = { selected = null },
            onRemoveDecision = if (item.isRemovable) {
                { confirmRemove = item }
            } else {
                null
            },
        )
    }

    confirmRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Parar de lembrar esta decisão?") },
            text = {
                Text(
                    "Esta decisão deixará de ser usada como orientação confirmada " +
                        "pelo Mentor. Ela continuará no histórico da memória como " +
                        "removida.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveDecision(item.id)
                    confirmRemove = null
                    selected = null
                }) { Text("Parar de lembrar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun MemorySectionHeader(title: String, count: Int) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecisionMemoryRow(
    item: DecisionMemoryItemUi,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemoryStatus(item.statusLabel, item.tone)
                Text(
                    item.sectionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${item.originLabel} • ${item.dateLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                item.applicationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryStatus(label: String, tone: DecisionMemoryTone) {
    val colors = when (tone) {
        DecisionMemoryTone.ACTIVE ->
            MaterialTheme.colorScheme.primaryContainer to
                MaterialTheme.colorScheme.onPrimaryContainer
        DecisionMemoryTone.ATTENTION ->
            MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer
        DecisionMemoryTone.HISTORY ->
            MaterialTheme.colorScheme.surfaceVariant to
                MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = colors.first,
        contentColor = colors.second,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun DecisionMemoryDetailDialog(
    item: DecisionMemoryItemUi,
    onDismiss: () -> Unit,
    onRemoveDecision: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detalhes da decisão") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                DetailLine("Seção", item.sectionLabel)
                DetailLine("Situação", item.statusLabel)
                DetailLine("Origem", item.originLabel)
                DetailLine("Registrada em", item.dateLabel)
                DetailLine("Aplicação", item.applicationLabel)
                item.replacesText?.let { DetailLine("Substituiu", it) }
                item.replacedByText?.let { DetailLine("Substituída por", it) }
                Text(
                    MEMORY_LIMITATION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRemoveDecision != null) {
                    TextButton(onClick = onRemoveDecision) {
                        Text("Parar de lembrar")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
