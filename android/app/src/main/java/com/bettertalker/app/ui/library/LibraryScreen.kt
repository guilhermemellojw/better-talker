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
import com.bettertalker.app.data.catalog.CitedPublication
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.detectSymbol
import com.bettertalker.app.data.util.JW_FINDER_HOME
import com.bettertalker.app.ui.components.ByodNotice
import com.bettertalker.app.ui.jw.JwDownloadDialog

// P0: escolher qualquer arquivo; a validação é pela extensão em importUri.
private val PICKER_MIMES = arrayOf("*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    linkNoteId: String? = null,
    onOpenModel: () -> Unit = {},
) {
    val items by vm.items.collectAsState(initial = emptyList())
    val cited by vm.cited.collectAsState()
    val toast by vm.toast.collectAsState()
    val ctx = LocalContext.current
    var showJw by remember { mutableStateOf(false) }
    var jwUrl by remember { mutableStateOf(JW_FINDER_HOME) }
    // M4: alvo do "Restaurar arquivo" quando o anexo veio de outro aparelho
    // e não tem sourceUrl para reimportar.
    var repairTarget by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = repairTarget
        repairTarget = null
        if (uri != null) {
            if (target != null) vm.repairMissing(target, uri) else vm.importUri(uri)
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
    LaunchedEffect(Unit) { vm.refreshCited() }
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
                OutlinedButton(onClick = {
                    repairTarget = null
                    picker.launch(PICKER_MIMES)
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.UploadFile, null); Text(" Importar publicação")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Publicações: JWPUB > EPUB > PDF (PDF limitado). TNM: só EPUB. Esboço: JWPUB > DOCX (PDF fallback).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))

            Spacer(Modifier.height(12.dp))
            val requiredSymbols = RecommendedPublications.REQUIRED
            val requiredPubs = requiredSymbols.mapNotNull { sym ->
                RecommendedPublications.ALL.firstOrNull { it.symbol == sym }
            }
            val otherPubs = RecommendedPublications.ALL.filter { it.symbol !in requiredSymbols }
            val requiredReady = requiredPubs.count { findInLibrary(it.symbol, items) != null }
            val active = items.filter { it.status == "downloading" || it.status == "indexing" }
            val attention = items.filter { it.status == "missing" || it.status == "failed" }
            val catalogSymbols = RecommendedPublications.ALL.map { it.symbol }.toSet()
            val myFiles = items.filter { a ->
                val symbol = a.symbol ?: detectSymbol(a.fileName)
                symbol !in catalogSymbols &&
                    a.status !in setOf("downloading", "indexing", "missing", "failed")
            }
            val availablePubs = otherPubs.filter { findInLibrary(it.symbol, items) != null }
            val openPub: (RecommendedPub) -> Unit = { pub ->
                jwUrl = pub.downloadUrl.ifBlank { JW_FINDER_HOME }
                showJw = true
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "required-title") {
                    LibrarySectionHeader(
                        "Obrigatórias",
                        "$requiredReady de ${requiredPubs.size}",
                    )
                }
                items(requiredPubs, key = { "required-${it.symbol}" }) { pub ->
                    RecommendedPubCard(
                        pub = pub,
                        match = findInLibrary(pub.symbol, items),
                        linkNoteId = linkNoteId,
                        onDownload = { openPub(pub) },
                        onLink = { attId ->
                            vm.linkToNote(attId, linkNoteId.orEmpty())
                            onBack()
                        },
                    )
                }

                if (active.isNotEmpty()) {
                    item(key = "active-title") {
                        LibrarySectionHeader("Em andamento", active.size.toString())
                    }
                    items(active, key = { "active-${it.id}" }) { a ->
                        AttachmentRow(a, vm, linkNoteId, onBack,
                            onOpenPage = { src -> jwUrl = src; showJw = true },
                            onRestoreFile = { id -> repairTarget = id; picker.launch(PICKER_MIMES) },
                        )
                    }
                }

                if (availablePubs.isNotEmpty()) {
                    item(key = "available-title") {
                        LibrarySectionHeader("Disponíveis", availablePubs.size.toString())
                    }
                    items(availablePubs, key = { "available-${it.symbol}" }) { pub ->
                        RecommendedPubCard(
                            pub = pub,
                            match = findInLibrary(pub.symbol, items),
                            linkNoteId = linkNoteId,
                            onDownload = { openPub(pub) },
                            onLink = { attId ->
                                vm.linkToNote(attId, linkNoteId.orEmpty())
                                onBack()
                            },
                        )
                    }
                }

                if (cited.isNotEmpty()) {
                    item(key = "cited-title") {
                        LibrarySectionHeader("Citadas nas notas", cited.size.toString())
                    }
                    items(cited, key = { "cited-${it.editionKey}" }) { c ->
                        CitedPublicationCard(
                            cited = c,
                            onDownload = { url -> jwUrl = url; showJw = true },
                            onImport = { picker.launch(PICKER_MIMES) },
                        )
                    }
                }

                if (attention.isNotEmpty()) {
                    item(key = "attention-title") {
                        LibrarySectionHeader("Precisa de atenção", attention.size.toString())
                    }
                    items(attention, key = { "attention-${it.id}" }) { a ->
                        AttachmentRow(a, vm, linkNoteId, onBack,
                            onOpenPage = { src -> jwUrl = src; showJw = true },
                            onRestoreFile = { id -> repairTarget = id; picker.launch(PICKER_MIMES) },
                        )
                    }
                }

                if (myFiles.isNotEmpty()) {
                    item(key = "myfiles-title") {
                        LibrarySectionHeader("Meus arquivos", myFiles.size.toString())
                    }
                    items(myFiles, key = { "my-${it.id}" }) { a ->
                        AttachmentRow(a, vm, linkNoteId, onBack,
                            onOpenPage = { src -> jwUrl = src; showJw = true },
                            onRestoreFile = { id -> repairTarget = id; picker.launch(PICKER_MIMES) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CitedPublicationCard(
    cited: CitedPublication,
    onDownload: (String) -> Unit,
    onImport: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(cited.label, style = MaterialTheme.typography.titleSmall)
                LibraryStatusPill(
                    if (cited.ambiguous) LibraryStatus.AMBIGUOUS else LibraryStatus.MISSING,
                )
                Text(
                    "Citada na nota",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                cited.hint?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (cited.downloadUrl != null) {
                TextButton(onClick = { onDownload(cited.downloadUrl) }) { Text("Baixar") }
            } else if (!cited.ambiguous) {
                TextButton(onClick = onImport) { Text("Importar publicação") }
            }
        }
    }
}

@Composable
private fun LibrarySectionHeader(title: String, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun AttachmentRow(
    a: AttachmentEntity,
    vm: LibraryViewModel,
    linkNoteId: String?,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onRestoreFile: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val slotTitle = BASE_PUBS.firstOrNull { it.slot == a.baseSlot }?.title
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(a.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${a.kind.uppercase()} • ${a.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                LibraryStatusPill(LibraryStatus.fromAttachment(a))
                val policy = com.bettertalker.app.data.util.PublicationFormatPolicy
                val isNwt = a.baseSlot == "nwt"
                val isS34 = policy.isS34File(a.fileName)
                val formatWarning = when {
                    isNwt && a.kind != "epub" ->
                        "TNM só EPUB — reimporte no formato correto."
                    isS34 -> policy.s34Warning(a.kind)
                    else -> policy.publicationWarning(a.kind)
                }
                if (formatWarning != null) {
                    Text(
                        formatWarning,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isNwt && a.kind != "epub") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
                if (slotTitle != null) {
                    Text(
                        "Base: $slotTitle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val policy = com.bettertalker.app.data.util.PublicationFormatPolicy
            val showSwap = a.status != "downloading" && a.status != "missing" &&
                (a.kind == "pdf" || (a.baseSlot == "nwt" && a.kind != "epub"))
            if (showSwap) {
                TextButton(onClick = { onRestoreFile(a.id) }) {
                    Text(
                        when {
                            a.baseSlot == "nwt" -> "Trocar por EPUB da TNM"
                            policy.isS34File(a.fileName) -> "Trocar arquivo do esboço"
                            else -> "Trocar por formato melhor"
                        }
                    )
                }
            }
            if (a.status == "missing") {
                if (!a.sourceUrl.isNullOrBlank()) {
                    TextButton(onClick = {
                        val src = a.sourceUrl.orEmpty()
                        if (src.startsWith("http") &&
                            !com.bettertalker.app.ui.jw.DownloadHelper.isPageUrl(src)
                        ) {
                            vm.redownloadDirect(a.id)
                        } else {
                            onOpenPage(src.ifBlank { JW_FINDER_HOME })
                        }
                    }) { Text("Importar da fonte") }
                } else {
                    TextButton(onClick = { onRestoreFile(a.id) }) {
                        Text("Restaurar arquivo")
                    }
                }
            }
            if (a.status == "failed" && a.appPath.isBlank()) {
                TextButton(onClick = { vm.retryRegister(a.id) }) { Text("Tentar de novo") }
                TextButton(onClick = {
                    val src = a.sourceUrl.orEmpty()
                    if (src.startsWith("http") &&
                        !com.bettertalker.app.ui.jw.DownloadHelper.isPageUrl(src)
                    ) {
                        vm.redownloadDirect(a.id)
                    } else {
                        onOpenPage(src.ifBlank { JW_FINDER_HOME })
                    }
                }) { Text("Baixar de novo") }
            } else if (a.status == "failed") {
                IconButton(onClick = { vm.reindex(a.id) }) {
                    Icon(Icons.Default.Refresh, "Tentar de novo")
                }
            }
            if (linkNoteId != null && a.noteId != linkNoteId) {
                TextButton(onClick = {
                    vm.linkToNote(a.id, linkNoteId)
                    onBack()
                }) { Text("Vincular") }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opções") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Reindexar") },
                    onClick = { menu = false; vm.reindex(a.id) },
                )
                BASE_PUBS.forEach { pub ->
                    DropdownMenuItem(
                        text = { Text("Marcar como: ${pub.title.take(28)}…") },
                        onClick = { menu = false; vm.markBase(a.id, pub.slot) },
                    )
                }
                if (a.baseSlot != null) {
                    DropdownMenuItem(
                        text = { Text("Remover marca base") },
                        onClick = { menu = false; vm.markBase(a.id, null) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Excluir") },
                    onClick = { menu = false; vm.delete(a.id) },
                )
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
    onLink: (String) -> Unit,
) {
    val alreadyInLibrary = match != null
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${pub.title} (${pub.symbol})", style = MaterialTheme.typography.titleSmall)
                LibraryStatusPill(
                    if (match != null) LibraryStatus.READY else LibraryStatus.MISSING,
                )
            }
            if (!alreadyInLibrary) {
                TextButton(onClick = onDownload) { Text("Baixar") }
            }
            if (match != null && linkNoteId != null && match.noteId != linkNoteId) {
                TextButton(onClick = { onLink(match.id) }) { Text("Vincular") }
            }
        }
    }
}
