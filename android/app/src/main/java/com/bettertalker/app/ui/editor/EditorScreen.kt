package com.bettertalker.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bettertalker.app.data.util.headingOffset
import com.bettertalker.app.ui.theme.NOTE_COLORS
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

// Paleta de cores da fonte (estilo Word)
private val FONT_COLORS = listOf(
    Color(0xFFD32F2F), Color(0xFFF57C00), Color(0xFFFBC02D),
    Color(0xFF388E3C), Color(0xFF1976D2), Color(0xFF7B1FA2),
    Color.Black
)
// Cores de marca-texto
private val HIGHLIGHT_COLORS = listOf(
    Color(0xFFFFFF00), Color(0xFFA5FF8A), Color(0xFF8AD4FF),
    Color(0xFFFFB3BA), Color(0xFFFFE08A)
)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onCopilot: () -> Unit, onAttach: () -> Unit) {
    val title by vm.title.collectAsState()
    val html by vm.html.collectAsState()
    val mdText by vm.mdText.collectAsState()
    val mdRevision by vm.mdRevision.collectAsState()
    val pending by vm.pendingInserts.collectAsState()
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
    var showColors by remember { mutableStateOf(false) }
    var showHighlight by remember { mutableStateOf(false) }
    var showSize by remember { mutableStateOf(false) }

    // Editor visual: HTML é a verdade; markdown é derivado p/ Copilot
    val richState = rememberRichTextState()

    // escrita externa (abertura, sync) -> renderiza; digitação local não reseta
    LaunchedEffect(mdRevision) {
        if (richState.toHtml() != html && html.isNotEmpty()) {
            richState.setHtml(html)
        } else if (html.isEmpty() && richState.annotatedString.text.isNotEmpty()) {
            richState.setMarkdown("")
        }
    }
    // digitação local -> exporta HTML + markdown com debounce
    LaunchedEffect(richState) {
        snapshotFlow { richState.annotatedString }
            .drop(1)
            .debounce(400)
            .collect { vm.onContent(richState.toHtml(), richState.toMarkdown()) }
    }
    // fila de inserções (Copilot, esboço): aplica na árvore viva, preserva estilos
    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        pending.forEach { ins ->
            val current = richState.annotatedString.text
            val at = ins.heading?.let { headingOffset(current, it) }
            if (at != null) {
                richState.insertMarkdown("\n\n" + ins.markdown.trim() + "\n", at)
            } else {
                richState.insertMarkdownAfterSelection(
                    (if (current.isBlank()) "" else "\n\n") + ins.markdown.trim() + "\n"
                )
            }
        }
        vm.consumePending()
    }

    val curSpan = richState.currentSpanStyle
    val curPara = richState.currentParagraphStyle
    val isBold = (curSpan.fontWeight?.weight ?: 400) > 400
    val isItalic = curSpan.fontStyle == FontStyle.Italic
    val isUnderline = curSpan.textDecoration?.contains(TextDecoration.Underline) == true
    val isStrike = curSpan.textDecoration?.contains(TextDecoration.LineThrough) == true

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
                    IconButton(onClick = {
                        if (!preview) vm.onContent(richState.toHtml(), richState.toMarkdown())
                        preview = !preview
                    }) {
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
                val wordCount = remember(mdText) {
                    mdText.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
                }
                BasicRichTextEditor(
                    state = richState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (richState.annotatedString.isEmpty()) {
                                Text(
                                    "Escreva aqui…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$wordCount palavras",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
                // Toolbar Word-like sobre a seleção (rolável p/ telas estreitas)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(end = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        ToolBtn(Icons.Default.FormatBold, "Negrito", active = isBold) {
                            richState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatItalic, "Itálico", active = isItalic) {
                            richState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatUnderlined, "Sublinhado", active = isUnderline) {
                            richState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatStrikethrough, "Tachado", active = isStrike) {
                            richState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        }
                    }
                    item {
                        ToolBtn(
                            Icons.Default.Palette, "Cor da fonte",
                            active = showColors
                        ) { showColors = !showColors; showHighlight = false; showSize = false }
                    }
                    item {
                        ToolBtn(
                            Icons.Default.FormatColorFill, "Marca-texto",
                            active = showHighlight
                        ) { showHighlight = !showHighlight; showColors = false; showSize = false }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatSize, "Tamanho", active = showSize) {
                            showSize = !showSize; showColors = false; showHighlight = false
                        }
                    }
                    item {
                        ToolBtn(
                            Icons.Default.FormatAlignLeft, "Alinhar",
                            active = curPara.textAlign == TextAlign.Center ||
                                curPara.textAlign == TextAlign.Right
                        ) {
                            val next = when (curPara.textAlign) {
                                TextAlign.Left -> TextAlign.Center
                                TextAlign.Center -> TextAlign.Right
                                else -> TextAlign.Left
                            }
                            richState.toggleParagraphStyle(ParagraphStyle(textAlign = next))
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.Title, "Título") {
                            richState.insertMarkdownAfterSelection("\n# ")
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatListBulleted, "Lista") {
                            richState.toggleUnorderedList()
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatListNumbered, "Numerada") {
                            richState.toggleOrderedList()
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatClear, "Limpar formatação") {
                            richState.toggleSpanStyle(
                                SpanStyle(
                                    color = Color.Unspecified,
                                    background = Color.Unspecified,
                                    fontWeight = FontWeight.Normal,
                                    fontStyle = FontStyle.Normal,
                                    textDecoration = TextDecoration.None,
                                    fontSize = androidx.compose.ui.unit.TextUnit.Unspecified
                                )
                            )
                        }
                    }
                }
                if (showColors) {
                    Spacer(Modifier.height(6.dp))
                    ColorDots(
                        colors = FONT_COLORS,
                        current = curSpan.color,
                        onPick = { richState.toggleSpanStyle(SpanStyle(color = it)) }
                    )
                }
                if (showHighlight) {
                    Spacer(Modifier.height(6.dp))
                    ColorDots(
                        colors = HIGHLIGHT_COLORS,
                        current = curSpan.background,
                        onPick = { richState.toggleSpanStyle(SpanStyle(background = it)) }
                    )
                }
                if (showSize) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("P" to 14.sp, "M" to 18.sp, "G" to 24.sp).forEach { (label, size) ->
                            val active = curSpan.fontSize == size
                            TextButton(onClick = {
                                richState.toggleSpanStyle(SpanStyle(fontSize = size))
                            }) {
                                Text(
                                    label,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
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
private fun ToolBtn(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            icon, desc,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ColorDots(
    colors: List<Color>,
    current: Color,
    onPick: (Color) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        (listOf(Color.Unspecified) + colors).forEach { c ->
            val selected = if (c == Color.Unspecified) current == Color.Unspecified
            else current == c
            Box(
                Modifier
                    .size(if (selected) 30.dp else 24.dp)
                    .clip(CircleShape)
                    .then(
                        if (c == Color.Unspecified) Modifier.border(
                            2.dp, MaterialTheme.colorScheme.outline, CircleShape
                        )
                        else Modifier.background(c)
                    )
                    .then(
                        if (selected) Modifier.border(
                            2.dp, MaterialTheme.colorScheme.primary, CircleShape
                        ) else Modifier
                    )
                    .clickable { onPick(c) }
            ) {
                if (c == Color.Unspecified) {
                    Icon(
                        Icons.Default.FormatClear, null,
                        modifier = Modifier.size(16.dp).align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
