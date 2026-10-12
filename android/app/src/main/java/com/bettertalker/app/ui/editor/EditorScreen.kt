package com.bettertalker.app.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bettertalker.app.data.export.buildExportHtml
import com.bettertalker.app.data.export.buildExportPlainText
import com.bettertalker.app.data.export.exportFileBaseName
import com.bettertalker.app.data.export.PdfExportManager
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.ui.theme.NOTE_COLORS
import com.mohamedrejeb.richeditor.model.RichTextState
import java.io.File

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

// 3.2.5g.2: tamanhos de fonte em EditorFontSizes.kt (compartilhados com o
// SectionCardEditor, que aplica o tamanho padrão do corpo).

/** 3.2.5g.2: picker ativo abaixo da toolbar (substitui os booleans antigos). */
private enum class PickerMode { FONT_COLOR, HIGHLIGHT, SIZE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onBack: () -> Unit,
    onAttach: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenStage: () -> Unit,
) {
    val title by vm.title.collectAsState()
    val mdText by vm.mdText.collectAsState()   // fallback do contador (sem seções)
    val saving by vm.saving.collectAsState()
    val color by vm.color.collectAsState()
    val attachments by vm.attachments.collectAsState(initial = emptyList())
    val outlineList by vm.outline.collectAsState(initial = emptyList())
    val folders by vm.folders.collectAsState(initial = emptyList())
    val noteFolderId by vm.folderId.collectAsState()
    val notePinned by vm.pinned.collectAsState()
    val sections by vm.sections.collectAsState()
    val sectionPendingInserts by vm.sectionPendingInserts.collectAsState()
    val draftState by vm.draftState.collectAsState()
    val canGenerateDraft by vm.canGenerateDraft.collectAsState()
    var preview by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 3.2.5g.2: picker ativo abaixo da toolbar (substitui showColors/showHighlight/showSize).
    var activePicker by remember { mutableStateOf<PickerMode?>(null) }
    // Menus dos slots da toolbar.
    var showSizeMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // 3.2.5g.3: menus do ⋮ que abrem dialog/sheet.
    var showColorDialog by remember { mutableStateOf(false) }
    var showAttachmentsSheet by remember { mutableStateOf(false) }
    // Exportação: sheet com Compartilhar / Salvar PDF / Copiar texto.
    var showExport by remember { mutableStateOf(false) }
    // Onboarding F2a: empurra seleção + prontidão sem travar a navegação.
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showOutlineDialog by remember { mutableStateOf(false) }

    // 3.2.5c: editor multi-seção. Duas noções independentes:
    // - activeSectionId: destaque visual do card tocado
    // - activeEditorState: editor focado (a toolbar opera nele; null = desabilitada)
    // É o MESMO RichTextState vivo do mapa do SectionCardEditor — atualizado
    // in-place quando o HTML externo muda (não precisa re-emitir).
    var activeSectionId by remember { mutableStateOf<String?>(null) }
    var activeEditorState by remember { mutableStateOf<RichTextState?>(null) }
    // F2c: chave do editor focado (fonte do alvo do FAB). O RichTextState
    // sozinho não diz a qual seção/sub-ponto pertence.
    var activeEditorKey by remember { mutableStateOf<String?>(null) }

    // 3.2.5g.2: preview esconde a toolbar — não deixar picker ativo vazar.
    LaunchedEffect(preview) { if (preview) activePicker = null }

    // 3.2.5e: inserts programáticos (Copilot/atalhos) são consumidos pelos
    // cards via sectionPendingInserts; a fila legada foi removida do VM.

    val curSpan = activeEditorState?.currentSpanStyle ?: SpanStyle()
    val isBold = (curSpan.fontWeight?.weight ?: 400) > 400
    val isItalic = curSpan.fontStyle == FontStyle.Italic
    val isUnderline = curSpan.textDecoration?.contains(TextDecoration.Underline) == true
    val isStrike = curSpan.textDecoration?.contains(TextDecoration.LineThrough) == true
    val toolbarEnabled = activeEditorState != null

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

    // 3.2.5g.3: cor da nota (item do ⋮; fecha ao escolher).
    if (showColorDialog) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text("Cor da nota") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NOTE_COLORS.forEach { c ->
                        NoteColorDot(
                            color = c,
                            selected = color == c.value.toLong(),
                            onClick = {
                                vm.setColor(c.value.toLong())
                                showColorDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorDialog = false }) { Text("Fechar") }
            },
        )
    }

    // 3.2.5g.3: esboço vinculado (item do ⋮; só aparece se houver esboço).
    outlineList.firstOrNull()?.let { o ->
        if (showOutlineDialog) {
            AlertDialog(
                onDismissRequest = { showOutlineDialog = false },
                title = { Text("Esboço vinculado") },
                text = { Text(o.title) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.unlinkOutline()
                        showOutlineDialog = false
                    }) { Text("Desvincular") }
                },
                dismissButton = {
                    TextButton(onClick = { showOutlineDialog = false }) { Text("Fechar") }
                },
            )
        }
    }

    // Contador de palavras: vivo das seções; fallback no mdText (sem seções).
    val wordCount = remember(sections, mdText) {
        if (sections.isEmpty()) countWords(mdText) else countSectionWords(sections)
    }

    // Recolhe a TopAppBar no scroll down; volta em qualquer scroll up.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // T1 — aguarda o push concluir antes de navegar (mesma
                    // race residual do botão do card, corrigida no T3).
                    scope.launch {
                        vm.pushContextForChat(
                            targetFromEditorKey(activeEditorKey) { subPointId ->
                                sections.firstOrNull { s ->
                                    s.subPoints.any { it.id == subPointId }
                                }?.section?.id
                            }
                        )
                        onOpenChat()
                    }
                }
            ) {
                Icon(Icons.Default.AutoAwesome, "Conversar com o Copilot")
            }
        },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = {
                    Column {
                        Text(
                            if (saving) "Salvando…" else "Salvo local",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "$wordCount palavras",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(if (preview) Icons.Default.Edit else Icons.Default.Visibility, "Preview")
                    }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Opções") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Cor da nota") },
                            onClick = { showMenu = false; showColorDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Anexos (${attachments.size})") },
                            onClick = { showMenu = false; showAttachmentsSheet = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar / Compartilhar") },
                            onClick = { showMenu = false; showExport = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Modo Palco") },
                            onClick = { showMenu = false; onOpenStage() },
                        )
                        outlineList.firstOrNull()?.let { o ->
                            DropdownMenuItem(
                                text = { Text("Esboço: ${o.title}") },
                                onClick = { showMenu = false; showOutlineDialog = true },
                            )
                        }
                        HorizontalDivider()
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .consumeWindowInsets(pad)
        ) {
            // Contador de palavras agora vive na TopAppBar (subtitle).
            // Toolbar fixa (não rola): com a TopAppBar recolhida assume o
            // topo, por isso o inset da status bar (consumido quando ela
            // ainda está expandida).
            if (!preview) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp),
            ) {
                // Toolbar Word-like fixa no topo (rolável p/ telas estreitas).
                // 3.2.5c: opera o editor ATIVO (activeEditorState); sem foco = desabilitada.
                // 3.2.5g.2: 8 slots (B I U S | 🎨 | 14 | ≡ | ⋯) + menus/pickers.
                val sizeLabel = when (curSpan.fontSize) {
                    FONT_SIZE_S -> "14"
                    FONT_SIZE_M -> "18"
                    FONT_SIZE_G -> "24"
                    // Sem span explícito = corpo no tamanho padrão.
                    else -> "18"
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(end = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        ToolBtn(Icons.Default.FormatBold, "Negrito", active = isBold, enabled = toolbarEnabled) {
                            activeEditorState?.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatItalic, "Itálico", active = isItalic, enabled = toolbarEnabled) {
                            activeEditorState?.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatUnderlined, "Sublinhado", active = isUnderline, enabled = toolbarEnabled) {
                            activeEditorState?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                        }
                    }
                    item {
                        ToolBtn(Icons.Default.FormatStrikethrough, "Tachado", active = isStrike, enabled = toolbarEnabled) {
                            activeEditorState?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        }
                    }
                    // "14": mostra o tamanho vigente e aplica S/M/G direto.
                    item {
                        Box {
                            TextButton(onClick = { showSizeMenu = true }, enabled = toolbarEnabled) {
                                Text(sizeLabel, fontWeight = FontWeight.SemiBold)
                            }
                            DropdownMenu(
                                expanded = showSizeMenu,
                                onDismissRequest = { showSizeMenu = false },
                            ) {
                                listOf(
                                    "Pequeno (14)" to FONT_SIZE_S,
                                    "Médio (18)" to FONT_SIZE_M,
                                    "Grande (24)" to FONT_SIZE_G,
                                ).forEach { (label, size) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            showSizeMenu = false
                                            activeEditorState?.toggleSpanStyle(SpanStyle(fontSize = size))
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // ⋯: formatação secundária (cor, marca-texto, listas,
                    // alinhamento) + limpar. F3.1: toolbar enxuta (B I U S |
                    // tamanho | ⋯) para caber em telas estreitas.
                    item {
                        Box {
                            ToolBtn(Icons.Default.MoreVert, "Mais opções", active = showMoreMenu, enabled = toolbarEnabled) {
                                showMoreMenu = true
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Cor de fonte") },
                                    onClick = { showMoreMenu = false; activePicker = PickerMode.FONT_COLOR },
                                )
                                DropdownMenuItem(
                                    text = { Text("Marca-texto") },
                                    onClick = { showMoreMenu = false; activePicker = PickerMode.HIGHLIGHT },
                                )
                                DropdownMenuItem(
                                    text = { Text("Tamanho") },
                                    onClick = { showMoreMenu = false; activePicker = PickerMode.SIZE },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Título") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeEditorState?.insertMarkdownAfterSelection("\n# ")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Lista") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeEditorState?.toggleUnorderedList()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Lista numerada") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeEditorState?.toggleOrderedList()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Alinhar à esquerda") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeEditorState?.toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Left))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Centralizar") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeEditorState?.toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Center))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Alinhar à direita") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeEditorState?.toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Right))
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Limpar formatação") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeEditorState?.toggleSpanStyle(
                                            SpanStyle(
                                                color = Color.Unspecified,
                                                background = Color.Unspecified,
                                                fontWeight = FontWeight.Normal,
                                                fontStyle = FontStyle.Normal,
                                                textDecoration = TextDecoration.None,
                                                fontSize = androidx.compose.ui.unit.TextUnit.Unspecified
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (toolbarEnabled) {
                    when (activePicker) {
                        PickerMode.FONT_COLOR -> {
                            Spacer(Modifier.height(6.dp))
                            ColorDots(
                                colors = FONT_COLORS,
                                current = curSpan.color,
                                onPick = { activeEditorState?.toggleSpanStyle(SpanStyle(color = it)) }
                            )
                        }
                        PickerMode.HIGHLIGHT -> {
                            Spacer(Modifier.height(6.dp))
                            ColorDots(
                                colors = HIGHLIGHT_COLORS,
                                current = curSpan.background,
                                onPick = { activeEditorState?.toggleSpanStyle(SpanStyle(background = it)) }
                            )
                        }
                        PickerMode.SIZE -> {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("P" to FONT_SIZE_S, "M" to FONT_SIZE_M, "G" to FONT_SIZE_G).forEach { (label, size) ->
                                    val active = curSpan.fontSize == size
                                    TextButton(onClick = {
                                        activeEditorState?.toggleSpanStyle(SpanStyle(fontSize = size))
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
                        null -> Unit
                    }
                }
            }
            }

            // 3.2.5c: corpo multi-seção (preview = readOnly em todos os cards).
            // Título + aviso + cards rolam juntos (título não fica fixo).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .then(
                        // Sem toolbar (preview), o topo recolhido precisa do
                        // inset da status bar aqui.
                        if (preview) Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        else Modifier
                    )
                    .padding(12.dp),
            ) {
                // Título estilo Notes: grande, sem borda
                androidx.compose.material3.TextField(
                    value = title, onValueChange = vm::onTitle,
                    placeholder = { Text("Título", style = MaterialTheme.typography.titleLarge) },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                // 3.2.5f-pre: aviso estrutural não-bloqueante (dispensável; a
                // próxima mudança republica se a estrutura seguir inválida).
                val structureWarning by vm.structureWarning.collectAsState()
                structureWarning?.let { w ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, "Aviso de estrutura")
                            Text(
                                text = structureWarningMessage(w.errors)
                                    ?: "A estrutura deste discurso pode precisar de ajustes.",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            IconButton(onClick = { vm.dismissStructureWarning() }) {
                                Icon(Icons.Default.Close, "Dispensar")
                            }
                        }
                    }
                }
            // Contador de palavras agora vive na TopAppBar (subtitle).

            // F3.x: orientações gerais do orador (NOTA do esboço + fechamento).
            // Distintas da introdução — material de apoio, recolhível.
            val speakerNotes by vm.speakerNotes.collectAsState()
            if (speakerNotes.isNotBlank()) {
                SpeakerNotesBlock(speakerNotes)
            }

            if (sections.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Comece a montar seu discurso",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Adicione uma introdução, os tópicos de desenvolvimento e a conclusão.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (!preview) {
                            Spacer(Modifier.height(16.dp))
                            AddSectionButton(
                                onAdd = { role -> vm.addSection(role, afterSectionId = null) },
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                    }
                }
            } else {
                    sections.forEachIndexed { index, sectionState ->
                        // key(id): estado do card não migra no reorder (3.2.5f.2b).
                        key(sectionState.section.id) {
                            SectionCardEditor(
                                state = sectionState,
                                isActiveSection = sectionState.section.id == activeSectionId,
                                readOnly = preview,
                                isFirstSection = index == 0,
                                isLastSection = index == sections.lastIndex,
                                onActivate = {
                                    activeSectionId = sectionState.section.id
                                    vm.setActiveSectionTitle(sectionState.section.title)
                                },
                                onTitleChange = { vm.onSectionTitle(sectionState.section.id, it) },
                                onMinutesChange = { vm.onSectionMinutes(sectionState.section.id, it) },
                                onContentChange = { vm.onSectionContent(sectionState.section.id, it) },
                                onActiveEditorChange = { key, state ->
                                    activeEditorKey = key
                                    activeEditorState = state
                                },
                                onSelectionChange = { ctx -> vm.onSelectionChange(ctx) },
                                sectionPendingInserts = sectionPendingInserts,
                                onConsumeInsert = { vm.consumeInsert(it) },
                                onAddSubPoint = { afterId ->
                                    vm.addSubPoint(sectionState.section.id, afterId)
                                },
                                onRemoveSubPoint = { spId -> vm.removeSubPoint(spId) },
                                onMoveSubPoint = { spId, dir -> vm.moveSubPoint(spId, dir) },
                                onUpdateSubPointOutlineText = { spId, text ->
                                    vm.updateSubPointOutlineText(spId, text)
                                },
                                onObjectiveChange = { vm.onSectionObjective(sectionState.section.id, it) },
                                onApproachChange = { vm.onSectionApproach(sectionState.section.id, it) },
                                onRoleChange = { role ->
                                    vm.updateSectionRole(sectionState.section.id, role)
                                },
                                onMoveSectionUp = {
                                    vm.moveSection(sectionState.section.id, MoveDirection.UP)
                                },
                                onMoveSectionDown = {
                                    vm.moveSection(sectionState.section.id, MoveDirection.DOWN)
                                },
                                onRemoveSection = { vm.removeSection(sectionState.section.id) },
                                canGenerateDraft = canGenerateDraft,
                                onGenerateDraft = vm::startDraft,
                                onChatAbout = { target ->
                                    // T3 — aguarda o push concluir antes de navegar:
                                    // sem await, o chat podia abrir com o contexto
                                    // ainda não empurrado (race do botão).
                                    scope.launch {
                                        vm.pushContextForChat(target)
                                        onOpenChat()
                                    }
                                },
                            )
                        }
                    }
                    if (!preview) {
                        AddSectionButton(
                            onAdd = { role ->
                                vm.addSection(role, afterSectionId = sections.lastOrNull()?.section?.id)
                            },
                        )
                    }
                }
            }
        }
    }

    // Fase 3.5e.3: preview do rascunho gerado pelo Copilot (fora do Scaffold).
    if (draftState !is DraftUiState.Idle) {
        DraftSheet(
            state = draftState,
            sections = sections,
            onAccept = vm::acceptDraft,
            onDismiss = vm::dismissDraft,
            onRetry = { (draftState as? DraftUiState.Error)?.target?.let(vm::startDraft) },
            onUndo = vm::undoDraft,
        )
    }

    // 3.2.5g.3: anexos da nota (substitui o rodapé fixo).
    if (showAttachmentsSheet) {
        AttachmentsSheet(
            attachments = attachments,
            onUnlink = { id -> vm.unlinkAttachment(id) },
            onAttach = { showAttachmentsSheet = false; onAttach() },
            onDismiss = { showAttachmentsSheet = false },
        )
    }

    // Exportação (T2/T3): Compartilhar HTML, Salvar PDF e Copiar texto.
    if (showExport) {
        ExportSheet(
            onDismiss = { showExport = false },
            onShare = {
                showExport = false
                shareExportHtml(ctx, title, sections)
            },
            onSavePdf = {
                showExport = false
                // T3: PrintManager + WebView offscreen — o usuário escolhe
                // "Salvar como PDF" e o destino no diálogo do sistema.
                PdfExportManager.printDiscurso(
                    ctx, title.ifBlank { "Discurso" }, exportHtmlOf(title, sections),
                )
            },
            onCopy = {
                showExport = false
                copyExportText(ctx, sections)
            },
        )
    }
}

/** 3.2.5g.3: bolinha de cor da nota (dialog do ⋮; sem swatch de "limpar"). */
@Composable
private fun NoteColorDot(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.onSurface,
                    CircleShape
                ) else Modifier
            )
            .clickable { onClick() }
    )
}

/**
 * F3.x: orientações gerais do orador — NOTA do esboço + fechamento. Não é
 * introdução; é material de apoio, recolhível por padrão.
 */
@Composable
private fun SpeakerNotesBlock(notes: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Orientações do orador",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            if (expanded) {
                Text(
                    notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 22.dp),
                )
            }
        }
    }
}

/**
 * 3.2.5f.2b: botão "Adicionar seção" com dropdown de role
 * (INTRO/BODY/CONCLUSION). Usado no fim da lista de seções e no empty
 * state (reuso). O caller decide se aparece (fora do preview).
 */
@Composable
private fun AddSectionButton(
    onAdd: (SectionRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedButton(
            onClick = { menuOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, "Adicionar parte")
            Spacer(Modifier.width(4.dp))
            Text("Adicionar parte")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            SectionRole.values().forEach { role ->
                DropdownMenuItem(
                    text = { Text(friendlyRoleMenuLabel(role)) },
                    onClick = { onAdd(role); menuOpen = false },
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled) {
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

// ---------- Exportação (T2): Compartilhar / Copiar ----------

/** HTML de exportação a partir do estado atual do editor. */
private fun exportHtmlOf(title: String, sections: List<SectionUiState>): String =
    buildExportHtml(
        noteTitle = title.ifBlank { "Discurso" },
        sections = sections.map { it.section },
        subPointsBySection = sections.associate { it.section.id to it.subPoints },
    )

/** T2 (Exportação) — grava o HTML no cache e abre o Share Intent (text/html). */
private fun shareExportHtml(ctx: Context, title: String, sections: List<SectionUiState>) {
    val html = exportHtmlOf(title, sections)
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    // Fix: mesmo helper do job name do PDF (legível, sem caracteres que quebram).
    val base = exportFileBaseName(title)
    val file = File(dir, "$base.html")
    file.writeText(html, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/html"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "Discurso" })
        putExtra(Intent.EXTRA_TITLE, title.ifBlank { "Discurso" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Compartilhar discurso"))
}

/** T2 (Exportação) — copia o texto puro do discurso (títulos + corpo). */
private fun copyExportText(ctx: Context, sections: List<SectionUiState>) {
    val text = buildExportPlainText(
        sections = sections.map { it.section },
        subPointsBySection = sections.associate { it.section.id to it.subPoints },
    )
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Discurso", text))
    Toast.makeText(ctx, "Texto copiado", Toast.LENGTH_SHORT).show()
}

/** Sheet de exportação: Compartilhar (HTML), Salvar PDF e Copiar texto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSavePdf: () -> Unit,
    onCopy: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Exportar discurso", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            ExportOption(
                Icons.Default.Share, "Compartilhar",
                "HTML do discurso (WhatsApp, Drive, e-mail)", onShare,
            )
            ExportOption(
                Icons.Default.PictureAsPdf, "Salvar PDF",
                "Escolha o destino no diálogo do sistema", onSavePdf,
            )
            ExportOption(
                Icons.Default.ContentCopy, "Copiar texto",
                "Texto do discurso sem formatação", onCopy,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Linha de ação do sheet de exportação. */
@Composable
private fun ExportOption(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
