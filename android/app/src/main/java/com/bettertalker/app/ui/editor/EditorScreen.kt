package com.bettertalker.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.theme.NOTE_COLORS
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onCopilot: () -> Unit, onAttach: () -> Unit) {
    val title by vm.title.collectAsState()
    val mdText by vm.mdText.collectAsState()
    val mdRevision by vm.mdRevision.collectAsState()
    val saving by vm.saving.collectAsState()
    val color by vm.color.collectAsState()
    val attachments by vm.attachments.collectAsState(initial = emptyList())
    val outlineList by vm.outline.collectAsState(initial = emptyList())
    val folders by vm.folders.collectAsState(initial = emptyList())
    val noteFolderId by vm.folderId.collectAsState()
    val notePinned by vm.pinned.collectAsState()
    var preview by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Editor visual: markdown é só o formato de troca (Copilot/sync)
    val richState = rememberRichTextState()

    // escrita externa (abertura, esboço, insert, sync) -> renderiza; digitação local não reseta
    LaunchedEffect(mdRevision) {
        if (richState.toMarkdown() != mdText) {
            richState.setMarkdown(mdText)
        }
    }
    // digitação local -> exporta markdown com debounce
    LaunchedEffect(richState) {
        snapshotFlow { richState.annotatedString }
            .drop(1)
            .debounce(400)
            .collect { vm.onMdText(richState.toMarkdown()) }
    }

    if (showMove) {
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("Mover para") },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            vm.moveToFolder(null); showMove = false
                        }.padding(vertical = 8.dp)
                    ) {
                        RadioButton(selected = noteFolderId == null, onClick = {
                            vm.moveToFolder(null); showMove = false
                        })
                        Spacer(Modifier.width(8.dp))
                        Text("Todas")
                    }
                    folders.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.moveToFolder(f.id); showMove = false
                            }.padding(vertical = 8.dp)
                        ) {
                            RadioButton(selected = noteFolderId == f.id, onClick = {
                                vm.moveToFolder(f.id); showMove = false
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(f.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMove = false }) { Text("Fechar") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir nota?") },
            text = { Text("A nota vai para a Lixeira.") },
            confirmButton = {
                TextButton(onClick = { vm.trashNote(); confirmDelete = false; onBack() }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") } },
                title = { Text(if (saving) "Salvando…" else "Salvo local") },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(if (preview) Icons.Default.Edit else Icons.Default.Visibility, "Preview")
                    }
                    IconButton(onClick = onCopilot) { Icon(Icons.Default.AutoAwesome, "Copilot") }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Opções") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Mover para pasta") },
                            onClick = { showMenu = false; showMove = true }
                        )
                        DropdownMenuItem(
                            text = { Text(if (notePinned) "Desafixar" else "Fixar") },
                            onClick = { showMenu = false; vm.togglePin() }
                        )
                        DropdownMenuItem(
                            text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; confirmDelete = true }
                        )
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            // Título estilo Notes: grande, sem borda
            androidx.compose.material3.TextField(
                value = title, onValueChange = vm::onTitle,
                placeholder = { Text("Título", style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            // Seletor de cor da nota
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NOTE_COLORS.forEach { c ->
                    val selected = color == c.value.toLong()
                    Box(
                        Modifier
                            .size(if (selected) 30.dp else 24.dp)
                            .clip(CircleShape)
                            .background(c)
                            .then(
                                if (selected) Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape
                                ) else Modifier
                            )
                            .clickable { vm.setColor(c.value.toLong()) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            outlineList.firstOrNull()?.let { o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📋 ${o.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCopilot) { Text("Ver seções") }
                    IconButton(onClick = { vm.unlinkOutline() }) {
                        Icon(Icons.Default.Close, "Desvincular esboço")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (!preview) {
                BasicRichTextEditor(
                    state = richState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
                Spacer(Modifier.height(8.dp))
                // Toolbar visual sobre a seleção
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ToolBtn(Icons.Default.FormatBold, "Negrito") {
                        richState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    }
                    ToolBtn(Icons.Default.FormatItalic, "Itálico") {
                        richState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    }
                    ToolBtn(Icons.Default.Title, "Título") {
                        richState.insertMarkdownAfterSelection("\n# ")
                    }
                    ToolBtn(Icons.Default.FormatListBulleted, "Lista") {
                        richState.toggleUnorderedList()
                    }
                    ToolBtn(Icons.Default.FormatListNumbered, "Numerada") {
                        richState.toggleOrderedList()
                    }
                    ToolBtn(Icons.Default.Checklist, "Checklist") {
                        richState.insertMarkdownAfterSelection("\n- [ ] ")
                    }
                    ToolBtn(Icons.Default.FormatQuote, "Citação") {
                        richState.insertMarkdownAfterSelection("\n> ")
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    MarkdownPreview(mdText)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = onAttach) {
                    Icon(Icons.Default.AttachFile, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Anexos da nota (${attachments.size})")
                }
            }
            attachments.take(4).forEach { a ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "• ${a.fileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.unlinkAttachment(a.id) }) {
                        Icon(Icons.Default.Close, "Desvincular")
                    }
                }
            }
            if (attachments.size > 4) {
                Text(
                    "+${attachments.size - 4} anexos (veja na Biblioteca)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun ToolBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc) }
}
