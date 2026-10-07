package com.bettertalker.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.catalog.RecommendedPub
import com.bettertalker.app.data.catalog.RecommendedPublications
import com.bettertalker.app.data.catalog.findInLibrary
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.JW_FINDER_HOME
import com.bettertalker.app.ui.components.ByodNotice
import com.bettertalker.app.ui.jw.JwDownloadDialog

private val PICKER_MIMES = arrayOf(
    "application/pdf",
    "application/epub+zip",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/rtf", "text/rtf",
    "application/zip", "application/x-zip-compressed",
    "text/plain", "application/octet-stream"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onBack: () -> Unit, linkNoteId: String? = null, onOpenModel: () -> Unit = {}) {
    val items by vm.items.collectAsState(initial = emptyList())
    val toast by vm.toast.collectAsState()
    val ctx = LocalContext.current
    var showJw by remember { mutableStateOf(false) }
    var jwUrl by remember { mutableStateOf(JW_FINDER_HOME) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    // Android 13+: notificação de conclusão do DownloadManager precisa de permissão
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { showJw = true }
    val openJw: () -> Unit = {
        jwUrl = JW_FINDER_HOME
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showJw = true
        }
    }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(toast) { if (toast.isNotEmpty()) { snack.showSnackbar(toast); vm.consumeToast() } }

    if (showJw) JwDownloadDialog(url = jwUrl, onDismiss = { showJw = false })

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") } },
                title = { Text("Biblioteca (local)") }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            if (linkNoteId != null) {
                Text(
                    "Toque Vincular para anexar à nota e voltar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            ByodNotice()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = openJw, modifier = Modifier.weight(1f)) {                    Icon(Icons.Default.Language, null); Text(" Abrir jw.org")
                }
                OutlinedButton(onClick = { picker.launch(PICKER_MIMES) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.UploadFile, null); Text(" Importar")
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onOpenModel, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.SmartToy, null); Text(" Modelo IA (local, opcional)")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "PDF, EPUB, DOCX, RTF, ZIP de RTFs, TXT e JWPUB.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
            // Achados em Downloads (ex: baixados no navegador, fora do app)
            val scan by vm.scan.collectAsState()
            OutlinedButton(
                onClick = { vm.scanDownloads() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, null); Text(" Procurar em Downloads")
            }
            scan?.let { found ->
                if (found.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Na pasta Downloads, ainda fora do app:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    found.forEach { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "• ${c.name}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.importScanned(c, linkNoteId) }) {
                                Text("Importar")
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                // T2 — catálogo curado (BYOD): metadados + links oficiais.
                item(key = "rec-title") {
                    Text("Publicações recomendadas", style = MaterialTheme.typography.titleMedium)
                }
                RecommendedPublications.byCategory().forEach { (category, pubs) ->
                    item(key = "rec-cat-$category") {
                        Text(
                            category,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    items(pubs, key = { "rec-${it.symbol}" }) { pub ->
                        val match = findInLibrary(pub.symbol, items)
                        RecommendedPubCard(
                            pub = pub,
                            match = match,
                            linkNoteId = linkNoteId,
                            onDownload = {
                                // T1: Baixar aponta para o jw.org (a WOL não baixa).
                                jwUrl = pub.downloadUrl.ifBlank { JW_FINDER_HOME }
                                showJw = true
                            },
                            onFormats = {
                                pub.formatsUrl?.let { u ->
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(u)
                                        )
                                    )
                                }
                            },
                            onLink = { attId ->
                                vm.linkToNote(attId, linkNoteId.orEmpty())
                                onBack()
                            }
                        )
                    }
                }
                item(key = "lib-title") {
                    Text("No acervo", style = MaterialTheme.typography.titleMedium)
                }
                if (items.isEmpty()) {
                    item(key = "lib-empty") {
                        Text(
                            "Nenhuma publicação. Baixe você mesmo do site oficial e importe.",
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                } else {
                    items(items, key = { it.id }) { a ->
                        var menu by remember { mutableStateOf(false) }
                        val slotTitle = BASE_PUBS.firstOrNull { it.slot == a.baseSlot }?.title
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        if (a.status == "downloading") "Baixando… ${a.fileName}"
                                        else "${a.kind.uppercase()} • ${a.sizeBytes / 1024} KB • " + when (a.status) {
                                            "ready" -> "Indexado"
                                            "failed" -> "Falha: ${a.error ?: "verifique o arquivo"}"
                                            else -> "Indexando…"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    if (slotTitle != null) {
                                        Text(
                                            "Base: $slotTitle",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (a.status == "failed" && a.appPath.isBlank()) {
                                    // nunca importado: retenta o registro ou baixa de novo
                                    TextButton(onClick = { vm.retryRegister(a.id) }) { Text("Tentar de novo") }
                                    TextButton(onClick = {
                                        val src = a.sourceUrl.orEmpty()
                                        if (src.startsWith("http") && !com.bettertalker.app.ui.jw.DownloadHelper.isPageUrl(src)) {
                                            vm.redownloadDirect(a.id)
                                        } else {
                                            jwUrl = src.ifBlank { JW_FINDER_HOME }
                                            showJw = true
                                        }
                                    }) { Text("Baixar de novo") }
                                } else if (a.status == "failed") {
                                    IconButton(onClick = { vm.reindex(a.id) }) { Icon(Icons.Default.Refresh, "Tentar de novo") }
                                }
                                if (linkNoteId != null && a.noteId != linkNoteId) {
                                    TextButton(onClick = {
                                        vm.linkToNote(a.id, linkNoteId)
                                        onBack()
                                    }) { Text("Vincular") }
                                }
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opções") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Reindexar") }, onClick = { menu = false; vm.reindex(a.id) })
                                    BASE_PUBS.forEach { pub ->
                                        DropdownMenuItem(
                                            text = { Text("Marcar como: ${pub.title.take(28)}…") },
                                            onClick = { menu = false; vm.markBase(a.id, pub.slot) }
                                        )
                                    }
                                    if (a.baseSlot != null) {
                                        DropdownMenuItem(text = { Text("Remover marca base") }, onClick = { menu = false; vm.markBase(a.id, null) })
                                    }
                                    DropdownMenuItem(text = { Text("Excluir") }, onClick = { menu = false; vm.delete(a.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** T2 — card do catálogo curado: badge de acervo + ações BYOD. */
@Composable
private fun RecommendedPubCard(
    pub: RecommendedPub,
    match: AttachmentEntity?,
    linkNoteId: String?,
    onDownload: () -> Unit,
    onFormats: () -> Unit,
    onLink: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${pub.title} (${pub.symbol})", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (match != null) "No acervo" else "Faltando",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (match != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary
                )
            }
            TextButton(onClick = onDownload) { Text("Baixar") }
            if (pub.formatsUrl != null) {
                TextButton(onClick = onFormats) { Text("Formatos") }
            }
            if (match != null && linkNoteId != null && match.noteId != linkNoteId) {
                TextButton(onClick = { onLink(match.id) }) { Text("Vincular") }
            }
        }
    }
}
