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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    "text/plain"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onBack: () -> Unit, linkNoteId: String? = null) {
    val items by vm.items.collectAsState(initial = emptyList())
    val toast by vm.toast.collectAsState()
    val ctx = LocalContext.current
    var showJw by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(toast) { if (toast.isNotEmpty()) { snack.showSnackbar(toast); vm.consumeToast() } }

    if (showJw) JwDownloadDialog(url = JW_FINDER_HOME, onDismiss = { showJw = false })

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
            ByodNotice()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { showJw = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Language, null); Text(" Abrir jw.org")
                }
                OutlinedButton(onClick = { picker.launch(PICKER_MIMES) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.UploadFile, null); Text(" Importar")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "PDF, EPUB, DOCX, RTF, ZIP de RTFs e TXT.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Text("Nenhuma publicação. Baixe você mesmo do site oficial e importe.", color = MaterialTheme.colorScheme.secondary)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { a ->
                        var menu by remember { mutableStateOf(false) }
                        val slotTitle = BASE_PUBS.firstOrNull { it.slot == a.baseSlot }?.title
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        "${a.kind.uppercase()} • ${a.sizeBytes / 1024} KB • " + when (a.status) {
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
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (a.status == "failed") {
                                    IconButton(onClick = { vm.reindex(a.id) }) { Icon(Icons.Default.Refresh, "Tentar de novo") }
                                }
                                if (linkNoteId != null && a.noteId != linkNoteId) {
                                    TextButton(onClick = { vm.linkToNote(a.id, linkNoteId) }) { Text("Vincular") }
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
