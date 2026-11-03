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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.catalog.RecommendedPub
import com.bettertalker.app.data.catalog.RecommendedPublications
import com.bettertalker.app.data.catalog.findInLibrary
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.prefs.DownloadsTreeStore
import com.bettertalker.app.data.repo.DownloadGroup
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.JW_FINDER_HOME
import com.bettertalker.app.ui.components.ByodNotice
import com.bettertalker.app.ui.jw.JwDownloadDialog
import kotlinx.coroutines.launch

// P0: escolher qualquer arquivo; a validação é pela extensão em importUri.
private val PICKER_MIMES = arrayOf("*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    linkNoteId: String? = null,
    /** A2: "Já baixei — procurar em Downloads" já abre a varredura. */
    scanOnOpen: Boolean = false,
    onOpenModel: () -> Unit = {},
) {
    val items by vm.items.collectAsState(initial = emptyList())
    val toast by vm.toast.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val treeUri by DownloadsTreeStore.getUri(ctx).collectAsState(initial = null)
    var showJw by remember { mutableStateOf(false) }
    var jwUrl by remember { mutableStateOf(JW_FINDER_HOME) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                DownloadsTreeStore.setUri(ctx, uri)
                vm.scanDownloads()
            }
        }
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
    LaunchedEffect(scanOnOpen) {
        if (scanOnOpen) {
            if (treeUri == null) treePicker.launch(null) else vm.scanDownloads()
        }
    }

    if (showJw) JwDownloadDialog(url = jwUrl, onDismiss = { showJw = false })

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
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
                Button(onClick = openJw, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Language, null); Text(" Abrir jw.org")
                }
                OutlinedButton(onClick = { picker.launch(PICKER_MIMES) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.UploadFile, null); Text(" Escolher arquivo")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "PDF, EPUB, DOCX, RTF, ZIP de RTFs, TXT e JWPUB.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))

            // P0 — pasta SAF explícita. Sem URI, o botão abre o seletor de
            // pasta; nunca cai em MediaStore silencioso.
            val scan by vm.scan.collectAsState()
            if (treeUri != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Pasta: ${treeUri?.lastPathSegment?.substringAfterLast(':') ?: "Downloads"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { treePicker.launch(null) }) { Text("Alterar") }
                }
            }
            OutlinedButton(
                onClick = {
                    if (treeUri == null || scan?.permissionLost == true) treePicker.launch(null)
                    else vm.scanDownloads()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, null)
                Text(" Ler pasta Downloads")
            }
            if (scan?.permissionLost == true) {
                Text(
                    "A permissão da pasta expirou. Escolha de novo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            scan?.takeIf { it.folderChosen && !it.permissionLost }?.let { found ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Encontradas em Downloads (${found.groups.size} publicações · ${found.fileCount} arquivos)",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (found.groups.isEmpty()) {
                    Text(
                        "Nada novo na pasta escolhida.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    found.groups.take(30).forEach { group ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${group.symbol} · ${group.formats}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    when {
                                        group.alreadyInLibrary -> "Já no acervo"
                                        group.duplicateCount > 0 ->
                                            "Novo · ${group.duplicateCount + 1} arquivos; importar ${group.best.name.substringAfterLast('.')}"
                                        else -> "Novo"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (group.alreadyInLibrary) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            TextButton(
                                onClick = { vm.importGroup(group, linkNoteId) },
                                enabled = !group.alreadyInLibrary,
                            ) { Text(if (group.alreadyInLibrary) "Já no acervo" else "Importar") }
                        }
                    }
                    if (found.groups.size > 30) {
                        Text(
                            "Mais ${found.groups.size - 30} publicações — use “Importar todas”.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (found.newGroups.isNotEmpty()) {
                            TextButton(onClick = { vm.importAllNew(linkNoteId) }) {
                                Text("Importar todas (${found.newGroups.size})")
                            }
                        }
                        TextButton(onClick = { vm.dismissScan() }) { Text("Ignorar") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val groupsBySymbol = scan?.groups?.associateBy { it.symbol }.orEmpty()
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
                            downloadGroup = groupsBySymbol[pub.symbol],
                            linkNoteId = linkNoteId,
                            onImportFromDownloads = { group ->
                                vm.importGroup(group, linkNoteId)
                            },
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
                    Text("Disponível", style = MaterialTheme.typography.titleMedium)
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
                                            "missing" -> "Falta o arquivo neste aparelho"
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
                                if (a.status == "missing" && !a.sourceUrl.isNullOrBlank()) {
                                    // Metadados vieram de outro aparelho; o
                                    // binário nunca sobe. Reimporta da fonte.
                                    TextButton(onClick = {
                                        val src = a.sourceUrl.orEmpty()
                                        if (src.startsWith("http") &&
                                            !com.bettertalker.app.ui.jw.DownloadHelper.isPageUrl(src)
                                        ) {
                                            vm.redownloadDirect(a.id)
                                        } else {
                                            jwUrl = src.ifBlank { JW_FINDER_HOME }
                                            showJw = true
                                        }
                                    }) { Text("Importar da fonte") }
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
    downloadGroup: DownloadGroup?,
    linkNoteId: String?,
    onImportFromDownloads: (DownloadGroup) -> Unit,
    onDownload: () -> Unit,
    onFormats: () -> Unit,
    onLink: (String) -> Unit,
) {
    val alreadyInLibrary = match != null || downloadGroup?.alreadyInLibrary == true
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${pub.title} (${pub.symbol})", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        match != null -> "Disponível"
                        downloadGroup?.alreadyInLibrary == true -> "Já no acervo"
                        else -> "Faltando"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (alreadyInLibrary) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary
                )
            }
            when {
                alreadyInLibrary -> Unit
                downloadGroup != null -> {
                    TextButton(onClick = { onImportFromDownloads(downloadGroup) }) {
                        Text("Importar do Downloads")
                    }
                }
                else -> TextButton(onClick = onDownload) { Text("Baixar") }
            }
            if (pub.formatsUrl != null) {
                TextButton(onClick = onFormats) { Text("Formatos") }
            }
            if (match != null && linkNoteId != null && match.noteId != linkNoteId) {
                TextButton(onClick = { onLink(match.id) }) { Text("Vincular") }
            }
        }
    }
}
