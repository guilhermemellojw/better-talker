package com.bettertalker.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.bettertalker.app.data.util.groupNoteSources
import com.bettertalker.app.data.util.isPending
import com.bettertalker.app.ui.copilot.DownloadCtl
import kotlinx.coroutines.CoroutineScope

/**
 * A2 (confiança) — checklist preventivo de fontes da nota.
 *
 * Agrupado por publicação (TNM única, com subgrupos por livro) para não virar
 * uma lista única em notas S-34. Grupos >3 nascem fechados; ≤3 abertos; TNM
 * fechada. O checklist usa a mesma lista de refs do Copilot e reage aos
 * estados Baixando → Indexando → No acervo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(
    sources: NoteSources,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLibrary: () -> Unit,
    onFindDownloads: () -> Unit = {},
    downloadCtl: DownloadCtl? = null,
    scope: CoroutineScope? = null,
    onDownloaded: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val groups = remember(sources) { groupNoteSources(sources.sources) }
    // Mantém a expansão do usuário enquanto o status muda (download/indexação).
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val expandedBooks = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(groups) {
        groups.forEach {
            if (!expanded.containsKey(it.key)) {
                // TNM é a base e costuma ser grande: nasce fechada.
                expanded[it.key] = it.key != "pub:nwt" && it.total <= 3
            }
        }
        groups.flatMap { it.bookGroups }.forEach { book ->
            if (!expandedBooks.containsKey(book.book)) {
                expandedBooks[book.book] = book.total <= 3
            }
        }
    }
    if (downloadCtl != null && scope != null) downloadCtl.host(scope)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sources.total > 0) Modifier.fillMaxHeight(0.9f) else Modifier)
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
                    "${sources.resolved} de ${sources.total} no acervo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    groups.joinToString(" · ") { "${it.label} ${it.resolved}/${it.total}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (sources.pending > 0) {
                    Text(
                        "${sources.pending} fonte(s) baixando/indexando — o checklist atualiza sozinho.",
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
                        .weight(1f)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    groups.forEach { group ->
                        item(key = group.key) {
                            GroupHeader(
                                group = group,
                                expanded = expanded[group.key] == true,
                                onToggle = {
                                    expanded[group.key] = !(expanded[group.key] ?: false)
                                },
                                ctx = ctx,
                                downloadCtl = downloadCtl,
                                onDownloaded = onDownloaded,
                                onOpenLibrary = onOpenLibrary,
                            )
                        }
                        if (expanded[group.key] == true) {
                            if (group.kind == SourceKind.BIBLE && group.bookGroups.isNotEmpty()) {
                                group.bookGroups.forEach { book ->
                                    item(key = "${group.key}|${book.book}") {
                                        BookHeader(
                                            book = book,
                                            expanded = expandedBooks[book.book] == true,
                                            onToggle = {
                                                expandedBooks[book.book] =
                                                    !(expandedBooks[book.book] ?: false)
                                            },
                                        )
                                    }
                                    if (expandedBooks[book.book] == true) {
                                        itemsIndexed(
                                            book.sources,
                                            key = { index, source ->
                                                "${group.key}|${book.book}|$index|${source.ref}"
                                            },
                                        ) { _, source ->
                                            SourceRow(
                                                source = source,
                                                ctx = ctx,
                                                downloadCtl = downloadCtl,
                                                scope = scope,
                                                onDownloaded = onDownloaded,
                                                onOpenLibrary = onOpenLibrary,
                                                onFindDownloads = onFindDownloads,
                                                onRefresh = onRefresh,
                                            )
                                        }
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    group.sources,
                                    key = { index, source ->
                                        "${group.key}|$index|${source.ref}|${source.status}"
                                    },
                                ) { _, source ->
                                    SourceRow(
                                        source = source,
                                        ctx = ctx,
                                        downloadCtl = downloadCtl,
                                        scope = scope,
                                        onDownloaded = onDownloaded,
                                        onOpenLibrary = onOpenLibrary,
                                        onFindDownloads = onFindDownloads,
                                        onRefresh = onRefresh,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onRefresh) { Text("Atualizar") }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: SourceGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    ctx: Context,
    downloadCtl: DownloadCtl?,
    onDownloaded: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val pending = group.pendingCount > 0
    val indexing = group.allPending && group.sources
        .filter { it.status.isPending() }
        .all { it.status == SourceStatus.INDEXING }
    val statusText = when {
        group.status == GroupStatus.OK -> "todas no acervo"
        group.allPending && indexing -> "indexando"
        group.allPending -> "baixando"
        group.status == GroupStatus.PARTIAL -> "algumas no acervo"
        else -> "nenhuma no acervo"
    }
    val statusTint = when {
        pending -> MaterialTheme.colorScheme.tertiary
        group.status == GroupStatus.OK -> MaterialTheme.colorScheme.secondary
        group.status == GroupStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable { onToggle() }
                .semantics {
                    val noun = if (group.total == 1) "referência" else "referências"
                    contentDescription = "${group.label}, ${group.total} $noun, $statusText"
                    stateDescription = if (expanded) "Expandido" else "Recolhido"
                },
        ) {
            StatusIcon(
                pending = pending,
                status = group.status,
                tint = statusTint,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${group.label} (${group.total})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            group.kind == SourceKind.BIBLE -> TextButton(
                onClick = onOpenLibrary,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Abrir Biblioteca") }
            pending && group.sources.none { it.status == SourceStatus.EDITION_MISSING } ->
                TextButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Abrir Biblioteca") }
            group.status != GroupStatus.OK -> {
                val actionSource = group.sources.firstOrNull {
                    it.status == SourceStatus.EDITION_MISSING
                }
                val url = (actionSource?.downloadUrl ?: actionSource?.refStatus?.downloadUrl)
                    ?.takeIf { it.isNotBlank() }
                TextButton(
                    onClick = {
                        openEdition(
                            ctx = ctx,
                            downloadCtl = downloadCtl,
                            url = url,
                            onDone = onDownloaded,
                            fallback = onOpenLibrary,
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (url != null) "Baixar no jw.org" else "Abrir Biblioteca")
                }
            }
        }
    }
}

@Composable
private fun BookHeader(
    book: BookGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val pending = book.pendingCount > 0
    val indexing = book.allPending && book.sources
        .filter { it.status.isPending() }
        .all { it.status == SourceStatus.INDEXING }
    val statusText = when {
        book.status == GroupStatus.OK -> "todas no acervo"
        book.allPending && indexing -> "indexando"
        book.allPending -> "baixando"
        book.status == GroupStatus.PARTIAL -> "algumas no acervo"
        else -> "nenhuma no acervo"
    }
    val statusTint = when {
        pending -> MaterialTheme.colorScheme.tertiary
        book.status == GroupStatus.OK -> MaterialTheme.colorScheme.secondary
        book.status == GroupStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onToggle() }
            .semantics {
                val noun = if (book.total == 1) "referência" else "referências"
                contentDescription = "${book.label}, ${book.total} $noun, $statusText"
                stateDescription = if (expanded) "Expandido" else "Recolhido"
            },
    ) {
        Spacer(Modifier.width(28.dp))
        StatusIcon(
            pending = pending,
            status = book.status,
            tint = statusTint,
            size = 18.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${book.label} (${book.total})",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusIcon(
    pending: Boolean,
    status: GroupStatus,
    tint: androidx.compose.ui.graphics.Color,
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
                GroupStatus.MISSING -> Icons.Default.Download
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun SourceRow(
    source: NoteSource,
    ctx: Context,
    downloadCtl: DownloadCtl?,
    scope: CoroutineScope?,
    onDownloaded: () -> Unit,
    onOpenLibrary: () -> Unit,
    onFindDownloads: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                source.ref,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                sourceStatusLabel(source.status),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    source.status == SourceStatus.RESOLVED ->
                        MaterialTheme.colorScheme.secondary
                    source.status.isPending() -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
        source.hint?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
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
        when (source.status) {
            SourceStatus.EDITION_MISSING -> {
                val url = (source.downloadUrl ?: source.refStatus?.downloadUrl)
                    ?.takeIf { it.isNotBlank() }
                val canDirect = source.refStatus?.let {
                    it.apiPub != null || it.probePub != null
                } == true
                Row {
                    TextButton(
                        onClick = {
                            openEdition(
                                ctx = ctx,
                                downloadCtl = downloadCtl,
                                url = url,
                                onDone = onDownloaded,
                                fallback = onOpenLibrary,
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (url != null) "Baixar no jw.org" else "Abrir Biblioteca") }
                    if (downloadCtl != null && scope != null &&
                        source.refStatus != null && canDirect
                    ) {
                        TextButton(
                            onClick = {
                                downloadCtl.downloadDirect(
                                    ctx, scope, source.refStatus, onDownloaded,
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Baixar direto") }
                    }
                }
                TextButton(
                    onClick = onFindDownloads,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Já baixei — procurar em Downloads") }
            }
            SourceStatus.DOWNLOADING, SourceStatus.INDEXING -> {
                TextButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Acompanhar na Biblioteca") }
            }
            SourceStatus.INDEX_FAILED, SourceStatus.UNSUPPORTED -> {
                TextButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Abrir Biblioteca") }
            }
            SourceStatus.TEXT_NOT_FOUND -> {
                val url = (source.downloadUrl ?: source.refStatus?.downloadUrl)
                    ?.takeIf { it.isNotBlank() }
                Row {
                    TextButton(
                        onClick = {
                            openEdition(
                                ctx = ctx,
                                downloadCtl = downloadCtl,
                                url = url,
                                onDone = onDownloaded,
                                fallback = onOpenLibrary,
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Abrir edição") }
                    TextButton(
                        onClick = onRefresh,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Verificar novamente") }
                }
            }
            SourceStatus.RESOLVED -> Unit
        }
    }
}

/** Rótulo curto do status, usado na linha e nos labels de acessibilidade. */
internal fun sourceStatusLabel(status: SourceStatus): String = when (status) {
    SourceStatus.RESOLVED -> "No acervo"
    SourceStatus.EDITION_MISSING -> "Edição não importada"
    SourceStatus.TEXT_NOT_FOUND -> "Texto não encontrado"
    SourceStatus.DOWNLOADING -> "Baixando…"
    SourceStatus.INDEXING -> "Indexando…"
    SourceStatus.INDEX_FAILED -> "Indexação falhou"
    SourceStatus.UNSUPPORTED -> "Formato não suportado"
}

/**
 * Abre a página da edição no WebView (DownloadCtl). Sem URL/controle, cai na
 * Biblioteca; nunca dispara download direto por API como ação primária.
 */
private fun openEdition(
    ctx: Context,
    downloadCtl: DownloadCtl?,
    url: String?,
    onDone: () -> Unit,
    fallback: () -> Unit,
) {
    when {
        downloadCtl != null && url != null -> downloadCtl.openPage(ctx, url, onDone)
        url != null -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        else -> fallback()
    }
}
