package com.bettertalker.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.SourceStatus
import com.bettertalker.app.ui.copilot.DownloadCtl
import kotlinx.coroutines.CoroutineScope

/**
 * A2 (confiança) — checklist preventivo de fontes da nota.
 *
 * O Copilot continua igual; o sheet só mostra o que está no acervo e o que
 * falta baixar/importar. Ações de download reutilizam [DownloadCtl] quando o
 * caller passa um (editor com CopilotViewModel); sem ele, abrem a página
 * oficial no navegador.
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
    if (downloadCtl != null && scope != null) downloadCtl.host(scope)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Fontes da nota",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (sources.total == 0) {
                    "Esta nota não cita fontes. O Copilot vai usar princípios gerais."
                } else {
                    "${sources.resolved} de ${sources.total} fontes no acervo."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sources.total > 0 && sources.resolved == 0) {
                Text(
                    "Nenhuma fonte citada está no acervo. Baixe/importe para o Copilot citar com precisão.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val bibles = sources.sources.filter { it.kind == SourceKind.BIBLE }
            val publications = sources.sources.filter { it.kind == SourceKind.PUBLICATION }
            if (bibles.isNotEmpty()) {
                SectionTitle("Bíblia")
                bibles.forEach { source ->
                    SourceRow(
                        source = source,
                        ctx = ctx,
                        downloadCtl = downloadCtl,
                        scope = scope,
                        onDownloaded = onDownloaded,
                        onOpenLibrary = onOpenLibrary,
                        onRefresh = onRefresh,
                    )
                }
            }
            if (publications.isNotEmpty()) {
                SectionTitle("Publicações")
                publications.forEach { source ->
                    SourceRow(
                        source = source,
                        ctx = ctx,
                        downloadCtl = downloadCtl,
                        scope = scope,
                        onDownloaded = onDownloaded,
                        onOpenLibrary = onOpenLibrary,
                        onRefresh = onRefresh,
                    )
                }
            }

            HorizontalDivider()
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SourceRow(
    source: NoteSource,
    ctx: android.content.Context,
    downloadCtl: DownloadCtl?,
    scope: CoroutineScope?,
    onDownloaded: () -> Unit,
    onOpenLibrary: () -> Unit,
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
                when (source.status) {
                    SourceStatus.RESOLVED -> "No acervo"
                    SourceStatus.EDITION_MISSING -> "Edição não importada"
                    SourceStatus.TEXT_NOT_FOUND -> "Texto não encontrado"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (source.status) {
                    SourceStatus.RESOLVED -> MaterialTheme.colorScheme.secondary
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
                            if (downloadCtl != null && source.refStatus != null && url != null) {
                                downloadCtl.openPage(ctx, url, onDownloaded)
                            } else if (downloadCtl != null && source.refStatus != null &&
                                canDirect && scope != null
                            ) {
                                downloadCtl.downloadDirect(ctx, scope, source.refStatus, onDownloaded)
                            } else if (url != null) {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                        enabled = url != null || (downloadCtl != null && canDirect),
                    ) { Text("Baixar/Importar") }
                    if (downloadCtl != null && scope != null && source.refStatus != null && canDirect) {
                        TextButton(onClick = {
                            downloadCtl.downloadDirect(ctx, scope, source.refStatus, onDownloaded)
                        }) { Text("Baixar direto") }
                    }
                    TextButton(onClick = onOpenLibrary) { Text("Abrir Biblioteca") }
                }
            }
            SourceStatus.TEXT_NOT_FOUND -> {
                Row {
                    TextButton(onClick = onOpenLibrary) { Text("Abrir edição") }
                    TextButton(onClick = onRefresh) { Text("Verificar") }
                }
            }
            SourceStatus.RESOLVED -> Unit
        }
    }
}
