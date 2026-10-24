// Editor de uma seção do discurso (INTRO/BODY/CONCLUSION).
package com.bettertalker.app.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.util.headingOffset
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SubPoint
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/**
 * F3.1 — o card do tópico comunica a hierarquia do produto:
 *
 *   TÓPICO
 *   ├── título + minutos
 *   ├── objetivo
 *   ├── linha de raciocínio (subtópicos numerados — estrutura, não redação)
 *   ├── ação: conversar com o Copilot
 *   ├── fontes e apoio (recolhível)
 *   ├── abordagem acordada (recolhível)
 *   └── mini discurso (o resultado)
 *
 * O sub-ponto deixou de ser um mini-editor: é uma linha da lista. O editor
 * rico vive só no mini discurso (e em INTRO/CONCLUSÃO). O `developedHtml`
 * legado continua no banco e é oferecido discretamente como "Nota antiga".
 */

@Composable
fun SectionCardEditor(
    state: SectionUiState,
    isActiveSection: Boolean,
    readOnly: Boolean = false,
    onActivate: () -> Unit,
    onTitleChange: (String) -> Unit,
    onMinutesChange: (Int) -> Unit,
    onContentChange: (String) -> Unit,
    onActiveEditorChange: (key: String?, richState: RichTextState?) -> Unit,
    onSelectionChange: (SelectionContext?) -> Unit,
    sectionPendingInserts: List<SectionAwareInsert> = emptyList(),
    onConsumeInsert: (SectionAwareInsert) -> Unit = {},
    onAddSubPoint: (afterSubPointId: String?) -> Unit = {},
    onRemoveSubPoint: (subPointId: String) -> Unit = {},
    onMoveSubPoint: (subPointId: String, direction: MoveDirection) -> Unit = { _, _ -> },
    onUpdateSubPointOutlineText: (subPointId: String, newText: String) -> Unit = { _, _ -> },
    isFirstSection: Boolean = false,
    isLastSection: Boolean = false,
    onRoleChange: (SectionRole) -> Unit = {},
    onMoveSectionUp: () -> Unit = {},
    onMoveSectionDown: () -> Unit = {},
    onRemoveSection: () -> Unit = {},
    /** Fase 1: único ponto de entrada do Copilot no card ativo (✨ no rodapé). */
    onCopilot: () -> Unit = {},
    /** UX de demonstrações (V&M): badge "Demonstração" no cabeçalho. */
    isDemonstration: Boolean = false,
    onApproveSection: () -> Unit = {},
    onUnapproveSection: () -> Unit = {},
    // #7d4: D&D de seções — o gesto vive no handle (≡); o EditorScreen é dono do estado.
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val editorStates = remember(state.section.id) {
        mutableStateMapOf<String, RichTextState>()
    }
    var focusedEditorKey by remember(state.section.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(state.section.id) { mutableStateOf(false) }
    // #7d3b: o título virou Text herói — a edição acontece por diálogo.
    var showTitleDialog by remember(state.section.id) { mutableStateOf(false) }
    // #7d3b-b: o chip de minutos do rodapé abre diálogo de edição.
    var showMinutesDialog by remember(state.section.id) { mutableStateOf(false) }

    val isBody = state.section.role == SectionRole.BODY

    Column {
        // #7d3b: título herói — Text (toque abre o diálogo de edição). Sem
        // chip "TÓPICO N"/papel e sem aprovar no card (gate fica no chat).
        Column(
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
                .then(
                    if (!isActiveSection) {
                        Modifier.clickable { onActivate() }
                    } else Modifier
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.section.title.ifBlank { "Título do tópico" },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.section.title.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    // Sem teto de linhas: a descrição da parte ("Colar e
                    // Pronto" v2) vive aqui — cortar esconderia as referências.
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !readOnly) { showTitleDialog = true },
                )
                if (isDemonstration) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            "Demonstração",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .animateContentSize()
                .then(
                    if (isActiveSection) Modifier.border(
                        width = 2.dp,
                        // exp/chat-contrast (2026-10-22): o primary (amarelo)
                        // como borda dava 1,23:1 no light (Samsung) — WCAG
                        // 1.4.11 exige 3:1; o tertiary passa nas 27 paletas.
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = RoundedCornerShape(12.dp),
                    ) else Modifier
                )
                .semantics { contentDescription = "Seção ${state.section.role.name}" }
                .clickable(enabled = !isActiveSection) { onActivate() },
        ) {
            // #7d3b: conteúdo + rodapé (handle ≡, minutos, ⋮) no pé do card.
            Column {
                Column(
                    Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp, bottom = 4.dp,
                    )
                ) {
                    // Inserts do chat → mini discurso do tópico (`section-{id}`).
                    LaunchedEffect(state.section.id, sectionPendingInserts) {
                        sectionPendingInserts
                            .filter { it.sectionId == state.section.id }
                            .forEach { insert ->
                                val targetEditorKey = insert.subPointId?.let { "subpoint-$it" }
                                    ?: "section-${state.section.id}"
                                val targetState = editorStates[targetEditorKey]
                                if (targetState != null) {
                                    val current = targetState.annotatedString.text
                                    val at = insert.heading?.let { headingOffset(current, it) }
                                    if (at != null) {
                                        targetState.insertMarkdown(
                                            "\n\n" + insert.markdown.trim() + "\n",
                                            at,
                                        )
                                    } else {
                                        targetState.insertMarkdownAfterSelection(
                                            (if (current.isBlank()) "" else "\n\n") +
                                                insert.markdown.trim() + "\n"
                                        )
                                    }
                                    // F2.3-fix: persiste imediatamente (o coletor
                                    // snapshotFlow do editor inicia depois e o
                                    // `.drop(1)` descarta a mudança).
                                    onContentChange(targetState.toHtml())
                                }
                                onConsumeInsert(insert)
                            }
                    }

                    when (state.section.role) {
                        SectionRole.INTRO, SectionRole.CONCLUSION -> {
                            val editorKey = "section-${state.section.id}"
                            SectionContentEditor(
                                editorKey = editorKey,
                                initialHtml = state.section.contentHtml,
                                sectionId = state.section.id,
                                subPointId = null,
                                editorStates = editorStates,
                                readOnly = readOnly,
                                onContentChange = onContentChange,
                                onSelectionChange = onSelectionChange,
                                onFocusChange = { isFocused ->
                                    if (isFocused) {
                                        focusedEditorKey = editorKey
                                        onActiveEditorChange(editorKey, editorStates[editorKey])
                                    } else if (focusedEditorKey == editorKey) {
                                        focusedEditorKey = null
                                        onActiveEditorChange(null, null)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp),
                            )
                        }

                        SectionRole.BODY -> {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                // UX de demonstrações: a linha de raciocínio
                                // (sub-pontos) não se aplica ao diálogo.
                                if (!isDemonstration) {
                                    ReasoningLine(
                                        subPoints = state.subPoints,
                                        readOnly = readOnly,
                                        onUpdateOutlineText = onUpdateSubPointOutlineText,
                                        onMoveSubPoint = onMoveSubPoint,
                                        onRemoveSubPoint = onRemoveSubPoint,
                                        onAddSubPoint = { onAddSubPoint(null) },
                                    )
                                }

                                MiniSpeechBlock(
                                    sectionId = state.section.id,
                                    contentHtml = state.section.contentHtml,
                                    targetMinutes = state.section.minutes,
                                    readOnly = readOnly,
                                    editorStates = editorStates,
                                    onContentChange = onContentChange,
                                    onSelectionChange = onSelectionChange,
                                    isDemonstration = isDemonstration,
                                    onFocusChange = { isFocused, key ->
                                        if (isFocused) {
                                            focusedEditorKey = key
                                            onActiveEditorChange(key, editorStates[key])
                                        } else if (focusedEditorKey == key) {
                                            focusedEditorKey = null
                                            onActiveEditorChange(null, null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // #7d3b: rodapé — handle (D&D) + minutos (BODY, se > 0) + ⋮.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                ) {
                    SectionDragHandle(
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                    )
                    // UX de demonstrações: atalhos de papel no rodapé do card
                    // (inserem o rótulo em negrito no editor do diálogo).
                    if (isDemonstration && isBody && !readOnly) {
                        val dialogKey = "section-${state.section.id}"
                        Spacer(Modifier.width(4.dp))
                        AssistChip(
                            onClick = {
                                editorStates[dialogKey]
                                    ?.insertHtmlAfterSelection("<b>Publicador:</b> ")
                            },
                            label = { Text("+ Publicador") },
                        )
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = {
                                editorStates[dialogKey]
                                    ?.insertHtmlAfterSelection("<b>Morador:</b> ")
                            },
                            label = { Text("+ Morador") },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (isBody && state.section.minutes > 0) {
                        // #7d3b-b: toque no chip abre o diálogo de edição
                        // (minutos não têm mais campo inline no card).
                        Surface(
                            onClick = { showMinutesDialog = true },
                            enabled = !readOnly,
                            shape = MaterialTheme.shapes.extraSmall,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                "${state.section.minutes} min",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (isActiveSection && !readOnly) {
                        // Fase 1: só o card ativo mostra o ✨; sem texto/borda.
                        IconButton(
                            onClick = onCopilot,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Copilot deste ponto",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    SectionMenuButton(
                        sectionId = state.section.id,
                        readOnly = readOnly,
                        isFirst = isFirstSection,
                        isLast = isLastSection,
                        // #7d3b: o chip de papel saiu; INTRO/CONCLUSÃO mudam de
                        // papel pelo ⋮ (mesma opção que o BODY já tinha).
                        showRoleChange = true,
                        onRoleChange = onRoleChange,
                        onMoveUp = onMoveSectionUp,
                        onMoveDown = onMoveSectionDown,
                        onRemove = { confirmDelete = true },
                        showUnapprove = state.section.approvedAt != null,
                        onUnapprove = onUnapproveSection,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        val extra = cascadeWarningMessage(state.subPoints.size)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir tópico?") },
            text = { Text("Esta parte será removida.$extra") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveSection()
                    confirmDelete = false
                }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
        )
    }

    // #7d3b: edição do título (o herói do card é Text; salvar chama
    // onTitleChange).
    if (showTitleDialog) {
        var draft by remember(state.section.id) { mutableStateOf(state.section.title) }
        val titleFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showTitleDialog = false },
            title = { Text("Editar título") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(titleFocus),
                )
                LaunchedEffect(Unit) { titleFocus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Título é uma linha lógica: quebra de linha vira espaço.
                    onTitleChange(draft.replace("\n", " ").trim())
                    showTitleDialog = false
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showTitleDialog = false }) { Text("Cancelar") }
            },
        )
    }

    // #7d3b-b: edição dos minutos pelo chip do rodapé (Salvar chama
    // onMinutesChange; campo vazio não altera).
    if (showMinutesDialog) {
        var draft by remember(state.section.id) { mutableStateOf(state.section.minutes.toString()) }
        val minutesFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showMinutesDialog = false },
            title = { Text("Editar minutos") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { input -> draft = input.filter { it.isDigit() }.take(2) },
                    singleLine = true,
                    suffix = { Text("min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(minutesFocus),
                )
                LaunchedEffect(Unit) { minutesFocus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = {
                    draft.toIntOrNull()?.let { onMinutesChange(it) }
                    showMinutesDialog = false
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showMinutesDialog = false }) { Text("Cancelar") }
            },
        )
    }
}

/** LINHA DE RACIOCÍNIO — subtópicos como lista numerada (estrutura). */
@Composable
private fun ReasoningLine(
    subPoints: List<SubPoint>,
    readOnly: Boolean,
    onUpdateOutlineText: (String, String) -> Unit,
    onMoveSubPoint: (String, MoveDirection) -> Unit,
    onRemoveSubPoint: (String) -> Unit,
    onAddSubPoint: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "LINHA DE RACIOCÍNIO",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subPoints.isEmpty()) {
            Text(
                "Adicione os subtópicos que orientam este tópico.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            subPoints.forEachIndexed { index, sp ->
                key(sp.id) {
                    SubPointRow(
                        index = index,
                        subPoint = sp,
                        isFirst = index == 0,
                        isLast = index == subPoints.lastIndex,
                        readOnly = readOnly,
                        onUpdateOutlineText = { onUpdateOutlineText(sp.id, it) },
                        onMoveUp = { onMoveSubPoint(sp.id, MoveDirection.UP) },
                        onMoveDown = { onMoveSubPoint(sp.id, MoveDirection.DOWN) },
                        onRemove = { onRemoveSubPoint(sp.id) },
                    )
                }
            }
        }
        if (!readOnly) {
            TextButton(
                onClick = onAddSubPoint,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Adicionar subtópico")
            }
        }
    }
}

/** Uma linha da linha de raciocínio: número + texto + menu estrutural. */
@Composable
private fun SubPointRow(
    index: Int,
    subPoint: SubPoint,
    isFirst: Boolean,
    isLast: Boolean,
    readOnly: Boolean,
    onUpdateOutlineText: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var editing by remember(subPoint.id) { mutableStateOf(false) }
    var draft by remember(subPoint.id) { mutableStateOf(subPoint.outlineText) }
    var menuOpen by remember(subPoint.id) { mutableStateOf(false) }
    var confirmDelete by remember(subPoint.id) { mutableStateOf(false) }
    var showOldNote by remember(subPoint.id) { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "${index + 1}.",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            // A11y: o amarelo primário dá 1.55:1 no tema claro; onSurfaceVariant
            // passa AA sobre o card (surfaceContainerHighest: 7.21 / 7.20).
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp).padding(top = if (editing) 14.dp else 1.dp),
        )
        Column(Modifier.weight(1f)) {
            if (editing) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("Subtítulo / ponto principal") },
                )
                Row {
                    TextButton(onClick = {
                        onUpdateOutlineText(draft)
                        editing = false
                    }) { Text("Salvar") }
                    TextButton(onClick = {
                        draft = subPoint.outlineText
                        editing = false
                    }) { Text("Cancelar") }
                }
            } else {
                Text(
                    text = subPoint.outlineText.ifBlank { "(sem texto)" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !readOnly) {
                            draft = subPoint.outlineText
                            editing = true
                        }
                        .padding(vertical = 3.dp),
                )
            }
            subPoint.instruction?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (subPoint.developedHtml.isNotBlank()) {
                TextButton(
                    onClick = { showOldNote = !showOldNote },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        if (showOldNote) "Ocultar nota antiga" else "Nota antiga do subtópico",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (showOldNote) {
                    Text(
                        stripHtml(subPoint.developedHtml),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
        if (!readOnly) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.MoreVert, "Menu do subtópico",
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Editar") },
                        onClick = {
                            draft = subPoint.outlineText
                            editing = true
                            menuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Mover para cima") },
                        enabled = !isFirst,
                        onClick = { onMoveUp(); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Mover para baixo") },
                        enabled = !isLast,
                        onClick = { onMoveDown(); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir") },
                        onClick = { confirmDelete = true; menuOpen = false },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir subtópico?") },
            text = { Text("A linha de raciocínio perde este ponto. A nota antiga (se houver) não é apagada agora.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove()
                    confirmDelete = false
                }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
        )
    }
}

/** MINI DISCURSO — o resultado do tópico, visualmente distinto. */
@Composable
private fun MiniSpeechBlock(
    sectionId: String,
    contentHtml: String,
    targetMinutes: Int,
    readOnly: Boolean,
    editorStates: MutableMap<String, RichTextState>,
    onContentChange: (String) -> Unit,
    onSelectionChange: (SelectionContext?) -> Unit,
    isDemonstration: Boolean = false,
    onFocusChange: (Boolean, String) -> Unit,
) {
    val hasText = contentHtml.isNotBlank()
    val miniKey = "section-$sectionId"
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // UX de demonstrações: sem rótulo "MINI DISCURSO" nem texto de ajuda
        // — o editor de diálogo é o protagonista do card.
        if (!isDemonstration) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "MINI DISCURSO",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    // A11y: primary amarelo falha AA no claro; onSurfaceVariant
                    // passa sobre o card (surfaceContainerHighest) e alinha com
                    // os demais rótulos (OBJETIVO, LINHA DE RACIOCÍNIO).
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasText) {
                    // T2: estimativa de tempo (130 palavras/min) comparada à meta
                    // do tópico (minutos do esboço).
                    val estimate = estimateMinutes(contentHtml)
                    val status = miniSpeechTimeStatus(estimate, targetMinutes)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        miniSpeechTimeLabel(estimate, targetMinutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (status) {
                            MiniSpeechTimeStatus.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                            MiniSpeechTimeStatus.LONG -> MaterialTheme.colorScheme.error
                            MiniSpeechTimeStatus.SHORT -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
            if (!hasText) {
                Text(
                    "Converse com o Copilot para desenvolver este tópico e depois crie o mini discurso.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionContentEditor(
            editorKey = miniKey,
            initialHtml = contentHtml,
            sectionId = sectionId,
            subPointId = null,
            editorStates = editorStates,
            readOnly = readOnly,
            onContentChange = onContentChange,
            onSelectionChange = onSelectionChange,
            onFocusChange = { onFocusChange(it, miniKey) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
        )
    }
}

/**
 * #7d4: handle de reordenação (≡). Long-press inicia o gesto; o componente
 * NÃO guarda estado — emite eventos para o EditorScreen (dono do drag).
 */
@Composable
private fun SectionDragHandle(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Icon(
        imageVector = Icons.Default.DragHandle,
        contentDescription = "Reordenar seção",
        modifier = Modifier
            .size(24.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    },
                )
            },
    )
}

@Composable
private fun SectionMenuButton(
    sectionId: String,
    readOnly: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    showRoleChange: Boolean = false,
    onRoleChange: (SectionRole) -> Unit = {},
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    // F3a: quando a seção está aprovada, o ⋮ oferece a reversão (o cabeçalho
    // mostra só o estado "Seção aprovada").
    showUnapprove: Boolean = false,
    onUnapprove: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (readOnly) return
    var menuOpen by remember(sectionId) { mutableStateOf(false) }
    var roleMenuOpen by remember(sectionId) { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.MoreVert,
                "Menu da seção",
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Mover para cima") },
                enabled = !isFirst,
                onClick = { onMoveUp(); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Mover para baixo") },
                enabled = !isLast,
                onClick = { onMoveDown(); menuOpen = false },
            )
            if (showUnapprove) {
                DropdownMenuItem(
                    text = { Text("Desaprovar seção") },
                    onClick = { onUnapprove(); menuOpen = false },
                )
            }
            // #7d2b: "Conversar sobre esta parte"/"Gerar com Copilot" saíram do ⋮
            // de INTRO/CONCLUSÃO — o chat agora é botão visível no corpo do card.
            if (showRoleChange) {
                DropdownMenuItem(
                    text = { Text("Alterar papel") },
                    onClick = {
                        menuOpen = false
                        roleMenuOpen = true
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                onClick = { onRemove(); menuOpen = false },
            )
        }
        if (showRoleChange) {
            DropdownMenu(
                expanded = roleMenuOpen,
                onDismissRequest = { roleMenuOpen = false },
            ) {
                SectionRole.values().forEach { role ->
                    DropdownMenuItem(
                        text = { Text(friendlyRoleName(role)) },
                        onClick = {
                            onRoleChange(role)
                            roleMenuOpen = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun SectionContentEditor(
    editorKey: String,
    initialHtml: String,
    sectionId: String,
    subPointId: String?,
    editorStates: MutableMap<String, RichTextState>,
    readOnly: Boolean,
    onContentChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSelectionChange: (SelectionContext?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // exp/chat-contrast (2026-10-22): o HTML salvo pode ter cor inline
    // escura (o S-34 em .docx traz <span style="color: rgba(123,31,162,1)">)
    // — roxo do S-34 sobre a surface escura dava 2,13:1 (invisível).
    // Sanitiza NA LEITURA (sem migração): no dark, cor escura vira a cor do
    // texto do tema; no light, o HTML fica intacto (o destaque sobrevive).
    val darkTheme = isSystemInDarkTheme()
    val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val displayHtml = remember(initialHtml, darkTheme, onSurfaceArgb) {
        com.bettertalker.app.data.util.HtmlInlineSanitizer.sanitizeRichHtmlForTheme(
            html = initialHtml,
            dark = darkTheme,
            fallbackArgb = onSurfaceArgb.toLong() and 0xFFFFFFFFL,
        )
    }
    val richState = remember(editorKey) {
        editorStates.getOrPut(editorKey) {
            RichTextState().apply { setHtml(displayHtml) }
        }
    }

    // #23-fix-c (H1 defensivo): rastreia foco LOCAL (richState.isFocused é
    // internal da lib). O eco do setHtml não pode atropelar quem digita.
    var isEditing by remember(editorKey) { mutableStateOf(false) }

    LaunchedEffect(editorKey, displayHtml) {
        if (!isEditing && richState.toHtml() != displayHtml) {
            richState.setHtml(displayHtml)
        }
    }

    LaunchedEffect(editorKey) {
        snapshotFlow { richState.annotatedString }
            .drop(1)
            .debounce(400)
            .collect { onContentChange(richState.toHtml()) }
    }

    LaunchedEffect(editorKey) {
        snapshotFlow { richState.selection }
            .debounce(200)
            .collect { sel ->
                // P1 (crash StringIndexOutOfBounds): o debounce pode entregar a
                // seleção defasada em relação ao texto atual — o corte usa
                // índices clampados E ordenados (nunca estoura).
                val full = richState.annotatedString.text
                val text = if (sel.collapsed) "" else {
                    safeSelectionSlice(full, sel.start, sel.end)
                }
                val fullHtml = richState.toHtml()
                if (text.isBlank() && fullHtml.isBlank()) {
                    onSelectionChange(null)
                } else {
                    onSelectionChange(
                        SelectionContext(
                            sectionId = sectionId,
                            subPointId = subPointId,
                            selectedText = text,
                            fullContentHtml = fullHtml,
                        )
                    )
                }
            }
    }

    key(editorKey) {
        Box {
            // #12: empty state do editor rich — some ao digitar; nunca no preview.
            if (!readOnly && richState.annotatedString.text.isEmpty()) {
                Text(
                    "Insira seu texto aqui",
                    style = MaterialTheme.typography.bodyLarge,
                    // exp/chat-contrast (2026-10-22): com alpha 0,5 o
                    // placeholder caía a 2,29:1 (sumia no dark).
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
            // Discovery #5: richeditor não herda LocalContentColor; sem cor
            // explícita o texto vira preto no dark. cursorBrush idem.
            BasicRichTextEditor(
                state = richState,
                enabled = !readOnly,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = FONT_SIZE_BODY_DEFAULT,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                modifier = modifier.onFocusChanged {
                    isEditing = it.isFocused
                    onFocusChange(it.isFocused)
                },
            )
        }
    }
}

/**
 * P1 (crash do editor — `StringIndexOutOfBoundsException: begin 633, end 464`):
 * trecho selecionado com índices SEMPRE seguros — clampa ao texto atual E
 * ordena (o debounce da seleção pode entregar start/end defasados do texto,
 * inclusive invertidos; clamp individual não bastava). Puro/testável.
 */
internal fun safeSelectionSlice(
    text: String,
    start: Int,
    end: Int,
    maxChars: Int = 2000,
): String {
    val a = start.coerceIn(0, text.length)
    val b = end.coerceIn(0, text.length)
    return text.substring(minOf(a, b), maxOf(a, b)).take(maxChars)
}
