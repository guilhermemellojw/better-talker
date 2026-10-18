package com.bettertalker.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.components.NoteCard
import com.bettertalker.app.ui.components.SyncTopBanner
import com.bettertalker.app.ui.components.SyncUiState
import com.bettertalker.app.ui.theme.NOTE_COLORS
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenNote: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTrash: () -> Unit,
    // #10b: Conta/Aparência/IA agora vivem na SettingsScreen (⚙).
    onOpenSettings: () -> Unit,
    /** "Inserir material → Escolher arquivo": importa o arquivo numa nota nova. */
    onImportMaterial: (android.net.Uri) -> Unit = {},
    /** "Colar texto": nota criada + aviso opcional (estrutura aplicada). */
    onPasteCreated: (String, String?) -> Unit = { id, _ -> onOpenNote(id) },
) {
    val notes by vm.notes.collectAsState()
    val folders by vm.folders.collectAsState()
    val query by vm.query.collectAsState()
    val folderId by vm.folderId.collectAsState()
    val counts by vm.attachCounts.collectAsState()
    val syncState by vm.syncState.collectAsState()
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showNewFolder by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDelFolder by remember { mutableStateOf(false) }
    // Fase 1 "Colar e Pronto": FAB abre as opções de criação.
    var showCreateSheet by remember { mutableStateOf(false) }
    var showInsertDialog by remember { mutableStateOf(false) }
    var showScratchDialog by remember { mutableStateOf(false) }
    // UX refactor: gerenciamento de arquivo no card (confirmação + mover).
    var confirmDelete by remember {
        mutableStateOf<com.bettertalker.app.data.db.NoteEntity?>(null)
    }
    var moveTarget by remember {
        mutableStateOf<com.bettertalker.app.data.db.NoteEntity?>(null)
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // "Inserir material → Escolher arquivo": picker nativo → importa na nota nova.
    val materialPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImportMaterial(uri) }
    val snack = remember { SnackbarHostState() }
    val selectedFolder = folders.firstOrNull { it.id == folderId }

    if (showRename && selectedFolder != null) {
        var name by remember(selectedFolder.id) { mutableStateOf(selectedFolder.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Renomear pasta") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) vm.renameFolder(selectedFolder.id, name.trim())
                    showRename = false
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancelar") } }
        )
    }

    if (confirmDelFolder && selectedFolder != null) {
        AlertDialog(
            onDismissRequest = { confirmDelFolder = false },
            title = { Text("Excluir pasta?") },
            text = { Text("“${selectedFolder.name}” será excluída; as notas voltam para Todas.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteFolder(selectedFolder.id); confirmDelFolder = false }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelFolder = false }) { Text("Cancelar") } }
        )
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        var color by remember { mutableStateOf(NOTE_COLORS.first().value.toLong()) }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("Nova pasta") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Nome") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NOTE_COLORS.forEach { c ->
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .clickable { color = c.value.toLong() }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) vm.newFolder(name.trim(), color)
                    showNewFolder = false
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancelar") } }
        )
    }

    // Fase 1 "Colar e Pronto": opções de criação agrupadas por intenção.
    if (showCreateSheet) {
        CreateNoteSheet(
            onDismiss = { showCreateSheet = false },
            onInsertMaterial = {
                showCreateSheet = false
                showInsertDialog = true
            },
            onStartFromScratch = {
                showCreateSheet = false
                showScratchDialog = true
            },
            onQuickNote = {
                showCreateSheet = false
                scope.launch { onOpenNote(vm.newNote()) }
            },
        )
    }
    if (showInsertDialog) {
        InsertMaterialDialog(
            onDismiss = { showInsertDialog = false },
            onPasteText = {
                showInsertDialog = false
                val clip = clipboard.getText()?.text?.trim().orEmpty()
                if (clip.isEmpty()) {
                    scope.launch { snack.showSnackbar("Nada para colar na área de transferência.") }
                } else {
                    scope.launch {
                        val outcome = vm.createNoteFromPaste(clip)
                        onPasteCreated(outcome.noteId, outcome.notice)
                    }
                }
            },
            onPickFile = {
                showInsertDialog = false
                materialPicker.launch(arrayOf("*/*"))
            },
        )
    }
    if (showScratchDialog) {
        StartFromScratchDialog(
            onDismiss = { showScratchDialog = false },
            onSpeech = {
                showScratchDialog = false
                scope.launch { onOpenNote(vm.createSpeechNote()) }
            },
            onDemo = {
                showScratchDialog = false
                scope.launch { onOpenNote(vm.createDemoNote()) }
            },
        )
    }
    // UX refactor: confirmação de exclusão (vai para a Lixeira, com Desfazer).
    confirmDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Excluir nota?") },
            text = { Text("\u201C${note.title.ifBlank { "Sem título" }}\u201D vai para a Lixeira.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = note.id
                    confirmDelete = null
                    vm.trash(id)
                    scope.launch {
                        val r = snack.showSnackbar(
                            "Nota movida para a Lixeira",
                            actionLabel = "Desfazer",
                            duration = androidx.compose.material3.SnackbarDuration.Short,
                        )
                        if (r == SnackbarResult.ActionPerformed) vm.restore(id)
                    }
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") }
            },
        )
    }
    // UX refactor: mover para pasta (mesma lista de pastas da Home).
    moveTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Mover para pasta") },
            text = {
                Column {
                    MoveOptionRow(
                        label = "Sem pasta",
                        selected = note.folderId == null,
                        onClick = {
                            vm.moveNote(note.id, null)
                            moveTarget = null
                        },
                    )
                    folders.forEach { f ->
                        MoveOptionRow(
                            label = f.name,
                            selected = note.folderId == f.id,
                            onClick = {
                                vm.moveNote(note.id, f.id)
                                moveTarget = null
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moveTarget = null }) { Text("Cancelar") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Better Talker") },
                actions = {
                    IconButton(onClick = onOpenLibrary) { Icon(Icons.Default.Book, "Biblioteca") }
                    IconButton(onClick = onOpenTrash) { Icon(Icons.Default.Delete, "Lixeira") }
                    // #10b: ⚙ abre a SettingsScreen (Conta/Aparência/IA).
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Configurações")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateSheet = true }) {
                Icon(Icons.Default.Add, "Nova nota")
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery,
                placeholder = { Text("Buscar notas…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Close, "Limpar busca")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(selected = folderId == null, onClick = { vm.setFolder(null) }, label = { Text("Todas") })
                }
                items(folders) { f ->
                    FilterChip(
                        selected = folderId == f.id, onClick = { vm.setFolder(if (folderId == f.id) null else f.id) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(f.colorArgb.toULong())))
                                Spacer(Modifier.width(6.dp))
                                Text(f.name)
                            }
                        }
                    )
                }
                item {
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, "Nova pasta")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (selectedFolder != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(
                        selectedFolder.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showRename = true }) { Text("Renomear") }
                    TextButton(onClick = { confirmDelFolder = true }) {
                        Text("Excluir", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) "Nada encontrado para “$query”."
                        else "Nenhuma nota. Toque + para criar.",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = syncState == SyncUiState.Syncing,
                    onRefresh = { vm.syncNow() },
                    // Indicador padrão (disco) substituído pelo banner premium
                    // que desliza do topo central.
                    indicator = {
                        SyncTopBanner(
                            state = syncState,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                ) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(notes, key = { it.id }) { n ->
                        NoteCard(
                            title = n.title, md = n.mdText, updatedAt = n.updatedAt,
                            pinned = n.pinned, attachCount = counts[n.id] ?: 0,
                            colorArgb = n.colorArgb,
                            onClick = { onOpenNote(n.id) },
                            onDelete = { confirmDelete = n },
                            onMove = { moveTarget = n },
                            onTogglePin = { vm.togglePin(n.id) }
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * Fase 1 "Colar e Pronto" (UX refactor): criação agrupada por intenção —
 * Inserir material (colar/arquivo) · Começar do zero (discurso/demo) ·
 * Nota rápida (discreta).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateNoteSheet(
    onDismiss: () -> Unit,
    onInsertMaterial: () -> Unit,
    onStartFromScratch: () -> Unit,
    onQuickNote: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Criar nota",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            CreateNoteOption(
                icon = Icons.Default.UploadFile,
                title = "Inserir material",
                subtitle = "Cole um texto ou escolha um arquivo",
                onClick = onInsertMaterial,
            )
            CreateNoteOption(
                icon = Icons.Default.Create,
                title = "Começar do zero",
                subtitle = "Discurso ou demonstração",
                onClick = onStartFromScratch,
            )
            // Ação secundária: discreta, alinhada à direita.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onQuickNote) { Text("Nota rápida") }
            }
        }
    }
}

/** "Inserir material": colar texto (detecção V&M) ou escolher arquivo. */
@Composable
private fun InsertMaterialDialog(
    onDismiss: () -> Unit,
    onPasteText: () -> Unit,
    onPickFile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inserir material") },
        text = {
            Column {
                CreateNoteOption(
                    icon = Icons.Default.ContentPaste,
                    title = "Colar texto",
                    subtitle = "Da área de transferência — detecta discurso ou demonstração",
                    onClick = onPasteText,
                )
                CreateNoteOption(
                    icon = Icons.Default.AttachFile,
                    title = "Escolher arquivo",
                    subtitle = "DOCX, PDF ou texto do esboço",
                    onClick = onPickFile,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** "Começar do zero": discurso (S-34) ou demonstração (V&M). */
@Composable
private fun StartFromScratchDialog(
    onDismiss: () -> Unit,
    onSpeech: () -> Unit,
    onDemo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Começar do zero") },
        text = {
            Column {
                CreateNoteOption(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "Discurso",
                    subtitle = "Estrutura de discurso (S-34)",
                    onClick = onSpeech,
                )
                CreateNoteOption(
                    icon = Icons.Default.Group,
                    title = "Demonstração",
                    subtitle = "Diálogo Publicador/Morador (Faça Seu Melhor)",
                    onClick = onDemo,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun CreateNoteOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Linha de escolha de pasta (dialog "Mover para pasta"). */
@Composable
private fun MoveOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Default.Check, "Atual", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
