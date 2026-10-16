package com.bettertalker.app.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import com.bettertalker.app.data.export.buildExportHtml
import com.bettertalker.app.data.export.buildExportPlainText
import com.bettertalker.app.data.export.exportFileBaseName
import com.bettertalker.app.data.export.PdfExportManager
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.ui.theme.NOTE_COLORS
import com.mohamedrejeb.richeditor.model.RichTextState
import java.io.File

// 3.2.5g.2: tamanhos de fonte em EditorFontSizes.kt (compartilhados com o
// SectionCardEditor, que aplica o tamanho padrão do corpo).

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onBack: () -> Unit,
    onAttach: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenStage: () -> Unit,
    // #7e2: empty state — o pipeline de esboço/paste vive no chat
    // (CopilotViewModel); a tela só dispara.
    onImportOutline: (Uri) -> Unit,
    onPasteOutline: (String) -> Unit,
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
    var preview by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 3.2.5g.3: menus do ⋮ que abrem dialog/sheet.
    var showColorDialog by remember { mutableStateOf(false) }
    var showAttachmentsSheet by remember { mutableStateOf(false) }
    // Exportação: sheet com Compartilhar / Salvar PDF / Copiar texto.
    var showExport by remember { mutableStateOf(false) }
    // #7e2: empty state — "Colar texto" e o menu do "Começar do zero".
    var showPasteDialog by remember { mutableStateOf(false) }
    var showAddSectionMenu by remember { mutableStateOf(false) }
    // #8a: "Anexar esboço" virou sheet com 2 caminhos (importar/colar).
    var showAttachSheet by remember { mutableStateOf(false) }
    // Onboarding F2a: empurra seleção + prontidão sem travar a navegação.
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showOutlineDialog by remember { mutableStateOf(false) }
    // #7e2: "Anexar esboço" reusa o pipeline do chat — mesmo contrato do
    // picker do ChatScreen (OpenDocument → CopilotViewModel.importOutlineFile).
    val outlinePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportOutline(uri)
            onOpenChat()
        }
    }
    // Modo guiado seção-a-seção: rolagem até o card focado pelo chat.
    // Sem LazyColumn (o Column atual preserva o estado dos editores):
    // o ScrollState é içado e cada card registra seu Y no conteúdo.
    val scrollState = rememberScrollState()
    // F5e (device): posições em coordenadas de JANELA. `positionInParent()`
    // mede contra um wrapper interno do card (valor quase constante), não
    // contra o conteúdo rolável — usar a janela + o topo do viewport é
    // imune ao aninhamento.
    val sectionWinY = remember { mutableStateMapOf<String, Int>() }
    var viewportTopWin by remember { mutableStateOf(0) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    // #7d4: estado do D&D de seções (dono: a tela; o handle só emite eventos).
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragStartWinY by remember { mutableFloatStateOf(0f) }
    val pendingScroll by vm.scrollRequests.collectAsState()
    LaunchedEffect(pendingScroll) {
        val id = pendingScroll ?: return@LaunchedEffect
        // F5e: espera o Y ESTABILIZAR (2 frames iguais) — o primeiro layout
        // pode reportar altura intermediária (cards ainda assentando).
        var last = -1
        var stable = 0
        var frames = 0
        while (stable < 2 && frames < 240) {
            withFrameNanos { }
            frames++
            val y = sectionWinY[id] ?: continue
            if (y == last) stable++ else { stable = 0; last = y }
        }
        if (last >= 0) {
            val target = (last - viewportTopWin + scrollState.value).coerceAtLeast(0)
            scrollState.animateScrollTo(target)
        }
        vm.consumeScrollRequest()
    }

    // #7d4: auto-scroll durante o drag — dedo perto do topo/base do viewport.
    // Compensa `dragOffsetY` para o card seguir sob o dedo enquanto o conteúdo rola.
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        while (true) {
            if (viewportHeightPx > 0f) {
                val fingerY = dragStartWinY + dragOffsetY
                val margin = 140f
                val delta = when {
                    fingerY < viewportTopWin + margin -> -14f
                    fingerY > viewportTopWin + viewportHeightPx - margin -> 14f
                    else -> 0f
                }
                if (delta != 0f) {
                    scrollState.scrollBy(delta)
                    dragOffsetY += delta
                }
            }
            withFrameNanos { }
        }
    }

    // 3.2.5c: editor multi-seção. Duas noções independentes:
    // - activeSectionId: destaque visual do card tocado
    // - activeEditorState: editor focado (a toolbar opera nele; null = desabilitada)
    // É o MESMO RichTextState vivo do mapa do SectionCardEditor — atualizado
    // in-place quando o HTML externo muda (não precisa re-emitir).
    var activeSectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeEditorState by remember { mutableStateOf<RichTextState?>(null) }
    // F2c: chave do editor focado (fonte do alvo do FAB). O RichTextState
    // sozinho não diz a qual seção/sub-ponto pertence.
    var activeEditorKey by remember { mutableStateOf<String?>(null) }

    // #7e2: o menu do empty state não pode ressurgir depois do preview.
    LaunchedEffect(preview) {
        if (preview) {
            showAddSectionMenu = false
        }
    }

    // 3.2.5e: inserts programáticos (Copilot/atalhos) são consumidos pelos
    // cards via sectionPendingInserts; a fila legada foi removida do VM.


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
    // F3a: progresso de aprovação (todas as seções contam — cada card tem o
    // toggle; regra escondida confundiria o contador).
    val approval = remember(sections) { approvalProgress(sections) }

    // Recolhe a TopAppBar no scroll down; volta em qualquer scroll up.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                        if (approval.total > 0) {
                            Text(
                                approvalLabel(approval),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (approval.isComplete) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                            text = { Text("Ensaio") },
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
                // #13-fix-a: toolbar extraída para FormattingToolbar.kt
                // (linha fina no phone + sheet; inline no tablet).
                // Opera o editor ATIVO; sem foco = desabilitada.
                EditorFormattingToolbar(
                    activeEditorState = activeEditorState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp),
                )
            }

            // 3.2.5c: corpo multi-seção (preview = readOnly em todos os cards).
            // Título + aviso + cards rolam juntos (título não fica fixo).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        viewportTopWin = coords.localToWindow(
                            androidx.compose.ui.geometry.Offset.Zero
                        ).y.toInt()
                        viewportHeightPx = coords.size.height.toFloat()
                    }
                    .verticalScroll(scrollState)
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
                // #7e2: dois caminhos — importar esboço (arquivo/colar) ou
                // montar do zero (menu de roles). O FAB não existe mais.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Comece a montar seu discurso",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Importe um esboço ou monte o seu do zero.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(Modifier.height(12.dp))

                        if (!preview) {
                            Button(
                                onClick = { showAttachSheet = true },
                                modifier = Modifier.fillMaxWidth(0.85f),
                            ) {
                                Icon(Icons.Default.AttachFile, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text(
                                        "Anexar esboço",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "Importar arquivo ou colar texto",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            Box {
                                OutlinedButton(
                                    onClick = { showAddSectionMenu = true },
                                    modifier = Modifier.fillMaxWidth(0.85f),
                                ) {
                                    Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text(
                                            "Começar do zero",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            "Adicionar introdução, tópicos e conclusão",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                AddSectionMenu(
                                    expanded = showAddSectionMenu,
                                    onDismiss = { showAddSectionMenu = false },
                                    onAdd = { role -> vm.addSection(role, afterSectionId = null) },
                                )
                            }
                        }
                    }
                }
            } else {
                    sections.forEachIndexed { index, sectionState ->
                        // key(id): estado do card não migra no reorder (3.2.5f.2b).
                        key(sectionState.section.id) {
                            val isDragging = draggingId == sectionState.section.id
                            // #7d4: o card arrastado translada/escala/sombreia; os
                            // demais ficam parados (v1 sem live preview).
                            Box(
                                modifier = Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffsetY else 0f
                                        val scale = if (isDragging) 1.02f else 1f
                                        scaleX = scale
                                        scaleY = scale
                                        shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                    },
                            ) {
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
                                    onApproveSection = { vm.approveSection(sectionState.section.id) },
                                    onUnapproveSection = { vm.unapproveSection(sectionState.section.id) },
                                    // #7d4: D&D — estado na tela; o handle só emite.
                                    onDragStart = {
                                        draggingId = sectionState.section.id
                                        dragStartWinY =
                                            (sectionWinY[sectionState.section.id] ?: 0).toFloat()
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { deltaY -> dragOffsetY += deltaY },
                                    onDragEnd = {
                                        val id = draggingId
                                        if (id != null) {
                                            val target = computeTargetIndex(
                                                draggedY = dragStartWinY + dragOffsetY,
                                                orderedSectionIds = sections.map { it.section.id },
                                                sectionWinY = sectionWinY.mapValues { it.value.toFloat() },
                                                draggedId = id,
                                            )
                                            if (target != null) vm.moveSectionTo(id, target)
                                        }
                                        draggingId = null
                                        dragOffsetY = 0f
                                    },
                                    // Modo guiado: registra o Y do card no conteúdo
                                    // rolável para o scroll pedido pelo chat.
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        sectionWinY[sectionState.section.id] = coords.localToWindow(
                                            androidx.compose.ui.geometry.Offset.Zero
                                        ).y.toInt()
                                    },
                                    onChatAbout = { target ->
                                        // #14-fix: "Conversar sobre este tópico"
                                        // ativa a seção (borda + canal de bloco
                                        // consistente) antes de empurrar.
                                        chatAboutActivation(
                                            target = target,
                                            sectionTitles = sections.associate {
                                                it.section.id to it.section.title
                                            },
                                        )?.let { (id, title) ->
                                            activeSectionId = id
                                            vm.setActiveSectionTitle(title)
                                        }
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

    // #8a: uma porta, dois caminhos — importar arquivo (picker existente)
    // ou colar texto (diálogo existente).
    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Anexar esboço", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachSheet = false
                            outlinePicker.launch(arrayOf("*/*"))
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Importar arquivo", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Selecione um .docx, .pdf ou texto",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachSheet = false
                            showPasteDialog = true
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Colar texto", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Cole aqui o material da reunião",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // #7e2: "Colar texto" do empty state — mesmo pipeline do chat
    // (CopilotViewModel.pasteOutline). "Analisar" segue para o chat, onde o
    // rascunho do esboço é exibido.
    if (showPasteDialog) {
        var pasteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Colar esboço") },
            text = {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    minLines = 6,
                    maxLines = 15,
                    placeholder = { Text("Cole aqui o texto do esboço ou da publicação...") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = pasteText
                    showPasteDialog = false
                    if (t.isNotBlank()) {
                        onPasteOutline(t)
                        onOpenChat()
                    }
                }) { Text("Analisar") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("Cancelar") }
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
 * (INTRO/BODY/CONCLUSION). Usado no fim da lista de seções. O caller
 * decide se aparece (fora do preview).
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
        AddSectionMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onAdd = onAdd,
        )
    }
}

/**
 * #7e2: dropdown de roles extraído para reuso — empty state ("Começar do
 * zero") e fim da lista (AddSectionButton).
 */
@Composable
private fun AddSectionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAdd: (SectionRole) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SectionRole.values().forEach { role ->
            DropdownMenuItem(
                text = { Text(friendlyRoleName(role)) },
                onClick = { onAdd(role); onDismiss() },
            )
        }
    }
}

// ---------- Exportação (T2): Compartilhar / Copiar ----------

/** HTML de exportação a partir do estado atual do editor. */
/**
 * #14-fix: alvo da ativação ao "Conversar sobre este tópico" — só seções
 * ativam (borda + canal de bloco); sub-ponto/nulo apenas empurram contexto.
 * Devolve (sectionId, título) ou null. Puro/testável.
 */
internal fun chatAboutActivation(
    target: DraftTarget?,
    sectionTitles: Map<String, String>,
): Pair<String, String>? = (target as? DraftTarget.Section)?.let { t ->
    sectionTitles[t.sectionId]?.let { t.sectionId to it }
}

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

/**
 * F3a: progresso de aprovação do discurso. Conta TODAS as seções (cada
 * card tem o toggle — excluir INTRO/CONCLUSION criaria regra escondida).
 * Puro/testável.
 */
data class ApprovalProgress(val approved: Int, val total: Int) {
    val isComplete: Boolean get() = total > 0 && approved >= total
}

/** Deriva o progresso da lista de seções (aprovada = `approvedAt != null`). Puro/testável. */
fun approvalProgress(sections: List<SectionUiState>): ApprovalProgress =
    ApprovalProgress(
        approved = sections.count { it.section.approvedAt != null },
        total = sections.size,
    )

/** Rótulo do topo: "X/Y seções aprovadas" ou "✓ Discurso pronto". Puro/testável. */
fun approvalLabel(progress: ApprovalProgress): String =
    if (progress.isComplete) "✓ Discurso pronto"
    else "${progress.approved}/${progress.total} seções aprovadas"
