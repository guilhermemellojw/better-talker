package com.bettertalker.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.util.BookGroup
import com.bettertalker.app.data.util.GroupStatus
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.SourceGroup
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.SourceStatus
import com.bettertalker.app.data.copilot.SourceRowAction
import com.bettertalker.app.data.copilot.downloadUrlFor
import com.bettertalker.app.data.copilot.sourceRowState
import com.bettertalker.app.data.util.citationStatusLabel
import com.bettertalker.app.data.util.groupNoteSources
import com.bettertalker.app.data.util.isPending
import com.bettertalker.app.ui.copilot.DownloadCtl
import kotlinx.coroutines.CoroutineScope

/**
 * A2 (confiança) — sheet de fontes da nota.
 *
 * Design limpo: uma linha por publicação/edição, não por referência.
 * Status em pílula curta (Disponível, Baixar, Indexando, Atenção).
 * Tudo colapsado por padrão; detalhes (refs, arquivo, motivo) só ao expandir.
 * Rodapé fixo: Abrir Biblioteca (primário), Atualizar, Fechar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(
    sources: NoteSources,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLibrary: () -> Unit,
    downloadCtl: DownloadCtl? = null,
    scope: CoroutineScope? = null,
    onDownloaded: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val groups = remember(sources) { groupNoteSources(sources.sources) }
    // Tudo colapsado por padrão (incluindo TNM).
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val expandedBooks = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(groups) {
        groups.forEach { group ->
            if (!expanded.containsKey(group.key)) {
                expanded[group.key] = false
            }
        }
        groups.flatMap { it.bookGroups }.forEach { book ->
            if (!expandedBooks.containsKey(book.book)) {
                expandedBooks[book.book] = false
            }
        }
    }
    if (downloadCtl != null && scope != null) downloadCtl.host(scope)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Fontes da nota",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (sources.total == 0) {
                Text(
                    "Esta nota não cita fontes. O Copilot vai usar princípios gerais.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    "${groups.size} publicações · ${sources.resolved} de ${sources.total} prontas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (sources.pending > 0) {
                    Text(
                        "${sources.pending} " +
                            com.bettertalker.app.data.util.plural(sources.pending, "fonte", "fontes") +
                            " baixando/indexando — o checklist atualiza sozinho.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (sources.resolved == 0 && sources.pending == 0) {
                    Text(
                        "Nenhuma fonte citada está no acervo. Baixe/importe para o Copilot citar com precisão.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    groups.forEachIndexed { index, group ->
                        item(key = group.key) {
                            PublicationRow(
                                group = group,
                                expanded = expanded[group.key] == true,
                                onToggle = {
                                    expanded[group.key] = !(expanded[group.key] ?: false)
                                },
                                expandedBooks = expandedBooks,
                                onToggleBook = { book ->
                                    expandedBooks[book] = !(expandedBooks[book] ?: false)
                                },
                                ctx = ctx,
                                downloadCtl = downloadCtl,
                                onDownloaded = onDownloaded,
                                onOpenLibrary = onOpenLibrary,
                                onRefresh = onRefresh,
                            )
                        }
                        if (index < groups.lastIndex) {
                            item(key = "divider-$index") {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenLibrary) {
                    Text("Abrir Biblioteca", fontWeight = FontWeight.SemiBold)
                }
                Row {
                    TextButton(onClick = onRefresh) { Text("Atualizar") }
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                }
            }
        }
    }
}

@Composable
private fun PublicationRow(
    group: SourceGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandedBooks: MutableMap<String, Boolean>,
    onToggleBook: (String) -> Unit,
    ctx: Context,
    downloadCtl: DownloadCtl?,
    onDownloaded: () -> Unit,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
) {
    val pending = group.pendingCount > 0
    // Badge = estado; botão = ação. Nunca usar o mesmo texto nos dois.
    val rowState = sourceRowState(group)
    val statusText = rowState.label
    val statusColor = when (rowState.action) {
        SourceRowAction.NONE -> MaterialTheme.colorScheme.secondary
        SourceRowAction.FOLLOW -> MaterialTheme.colorScheme.tertiary
        SourceRowAction.RESOLVE -> MaterialTheme.colorScheme.tertiary
        SourceRowAction.RETRY -> MaterialTheme.colorScheme.error
        // Conferir/Abrir e Falta/Parcial pedem atenção, não são falha.
        SourceRowAction.OPEN -> MaterialTheme.colorScheme.tertiary
        SourceRowAction.DOWNLOAD -> MaterialTheme.colorScheme.tertiary
    }
    val isAttention = rowState.attention

    Column(Modifier.fillMaxWidth()) {
        // Linha principal: publicação + status pill + ação
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onToggle() }
                .semantics {
                    contentDescription = "${group.label}, ${group.total} referências, $statusText"
                    stateDescription = if (expanded) "Expandido" else "Recolhido"
                },
        ) {
            // Status icon (compact)
            StatusPillIcon(pending = pending, status = group.status, tint = statusColor)
            Spacer(Modifier.width(8.dp))
            // Label + count
            Column(Modifier.weight(1f)) {
                Text(
                    group.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${group.total} ${if (group.total == 1) "referência" else "referências"}" +
                        if (group.kind == SourceKind.BIBLE) " · versículos" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Status pill
            Surface(
                color = statusColor.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            // Action button (only one per state)
            when (rowState.action) {
                SourceRowAction.NONE -> Unit
                SourceRowAction.FOLLOW -> TextButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Acompanhar") }
                SourceRowAction.RESOLVE -> {
                    // Usa a mesma busca do Baixar: qualquer fonte do grupo pode
                    // ter a URL canônica (ex.: it-1 → it; w08 15/8 → w20080615).
                    val url = downloadUrlFor(group)
                    TextButton(
                        onClick = {
                            if (url != null && downloadCtl != null) {
                                downloadCtl.openPage(ctx, url, onDownloaded)
                            } else {
                                onOpenLibrary()
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Resolver") }
                }
                SourceRowAction.RETRY -> TextButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Reindexar") }
                SourceRowAction.OPEN -> TextButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Abrir") }
                SourceRowAction.DOWNLOAD -> {
                    val url = downloadUrlFor(group)
                    TextButton(
                        onClick = {
                            if (url != null && downloadCtl != null) {
                                downloadCtl.openPage(ctx, url, onDownloaded)
                            } else {
                                // Nunca abrir a Biblioteca em silêncio por falta
                                // de URL: a Biblioteca é para gerenciar, não baixar.
                                android.widget.Toast.makeText(
                                    ctx,
                                    "Não consegui abrir a página desta edição.",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Baixar") }
                }
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        // Expanded details
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 4.dp, bottom = 4.dp),
            ) {
                if (group.kind == SourceKind.BIBLE && group.bookGroups.isNotEmpty()) {
                    group.bookGroups.forEach { book ->
                        BookDetailRow(
                            book = book,
                            expanded = expandedBooks[book.book] == true,
                            onToggle = { onToggleBook(book.book) },
                        )
                        if (expandedBooks[book.book] == true) {
                            book.sources.forEach { source ->
                                SourceDetailLine(source = source)
                            }
                        }
                    }
                } else {
                    group.sources.forEach { source ->
                        SourceDetailLine(source = source)
                    }
                }
                // Secondary actions
                if (group.status != GroupStatus.OK) {
                    Row(Modifier.padding(top = 4.dp)) {
                        TextButton(
                            onClick = onOpenLibrary,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Abrir Biblioteca") }
                        TextButton(
                            onClick = onOpenLibrary,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Importar publicação") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookDetailRow(
    book: BookGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable { onToggle() },
    ) {
        Text(
            "${book.label} (${book.total})",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            when (book.status) {
                GroupStatus.OK -> "Disponível"
                GroupStatus.PARTIAL -> "Parcial"
                GroupStatus.MISSING -> "Falta"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (book.status) {
                GroupStatus.OK -> MaterialTheme.colorScheme.secondary
                GroupStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
                // Falta pede download, não é falha.
                GroupStatus.MISSING -> MaterialTheme.colorScheme.tertiary
            },
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SourceDetailLine(source: NoteSource) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(
            source.ref,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        source.hint?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        source.fileName?.takeIf { it.isNotBlank() }?.let {
            Text(
                "Arquivo: $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun StatusPillIcon(
    pending: Boolean,
    status: GroupStatus,
    tint: Color,
    size: androidx.compose.ui.unit.Dp = 20.dp,
) {
    if (pending) {
        CircularProgressIndicator(
            modifier = Modifier.size(size),
            strokeWidth = 2.dp,
            color = tint,
        )
    } else {
        Icon(
            when (status) {
                GroupStatus.OK -> Icons.Default.CheckCircle
                GroupStatus.PARTIAL -> Icons.Default.Warning
                GroupStatus.MISSING -> Icons.Default.Warning
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

/** Rótulo curto do status, usado na linha e nos labels de acessibilidade. */
fun sourceStatusLabel(status: SourceStatus): String = when (status) {
    SourceStatus.RESOLVED -> "Disponível"
    SourceStatus.EDITION_MISSING -> "Edição não importada"
    SourceStatus.TEXT_NOT_FOUND -> "Texto não encontrado"
    SourceStatus.DOWNLOADING -> "Baixando…"
    SourceStatus.INDEXING -> "Indexando…"
    SourceStatus.INDEX_FAILED -> "Indexação falhou"
    SourceStatus.UNSUPPORTED -> "Formato não suportado"
}
