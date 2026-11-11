package com.bettertalker.app.ui.assistant

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.library.GlobalLibraryReaderContent
import com.bettertalker.app.data.library.GlobalLibrarySource

/** Lista de fontes do último turno; toque abre o reader da unidade. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSourcesSheet(
    sources: List<GlobalLibrarySource>,
    onSelect: (GlobalLibrarySource) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fontes (${sources.size})",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Fechar fontes")
                }
            }
            Text(
                "Toque em uma fonte para ler a unidade completa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(sources, key = { it.index }) { source ->
                    SourceCard(source = source, onClick = { onSelect(source) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SourceCard(
    source: GlobalLibrarySource,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "[Fonte ${source.index}]",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                if (source.training) {
                    AssistChip(
                        onClick = onClick,
                        label = { Text("Orientação de oratória") },
                    )
                }
            }
            Text(
                source.publicationTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val unit = buildList {
                source.unitLabel?.let { add(it) }
                source.unitTitle?.let { add(it) }
            }.joinToString(" — ")
            if (unit.isNotBlank()) {
                Text(
                    unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            source.locationLabel.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                source.snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Reader offline da unidade/passages da fonte. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceReaderSheet(
    state: GlobalReaderState?,
    onDismiss: () -> Unit,
    onSpeak: (GlobalLibraryReaderContent) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val source = when (state) {
        is GlobalReaderState.Loading -> state.source
        is GlobalReaderState.Content -> state.source
        is GlobalReaderState.Error -> state.source
        null -> return
    }
    val content = (state as? GlobalReaderState.Content)?.content
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ctx = LocalContext.current
    val unit = remember(source) {
        buildList {
            source.unitLabel?.let { add(it) }
            source.unitTitle?.let { add(it) }
        }.joinToString(" — ")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fonte ${source.index}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Fechar fonte")
                }
            }
            Text(
                source.publicationTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit.isNotBlank()) {
                Text(
                    unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (source.locationLabel.isNotBlank()) {
                Text(
                    source.locationLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (source.training) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Orientação de oratória — não é prova doutrinal.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(10.dp))
            when (state) {
                is GlobalReaderState.Loading -> {
                    Row(
                        Modifier.fillMaxWidth().height(120.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(10.dp))
                        Text("Abrindo fonte…")
                    }
                }
                is GlobalReaderState.Error -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                }
                is GlobalReaderState.Content -> {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SelectionContainer {
                            Text(
                                state.content.body,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (state.content.truncated) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Leitura limitada ao trecho desta unidade.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        content?.let {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("Fonte", it.body)
                            )
                            Toast.makeText(ctx, "Fonte copiada", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = content != null,
                ) {
                    Text("Copiar")
                }
                OutlinedButton(
                    onClick = { content?.let(onSpeak) },
                    enabled = content != null && content.body.isNotBlank(),
                ) {
                    Text("Ouvir")
                }
                TextButton(onClick = onOpenLibrary) {
                    Text("Abrir Biblioteca")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
