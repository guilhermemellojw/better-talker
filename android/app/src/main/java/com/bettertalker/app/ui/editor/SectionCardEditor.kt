// Editor de uma seção do discurso (INTRO/BODY/CONCLUSION).
package com.bettertalker.app.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onObjectiveChange: (String) -> Unit = {},
    onApproachChange: (String) -> Unit = {},
    isFirstSection: Boolean = false,
    isLastSection: Boolean = false,
    onRoleChange: (SectionRole) -> Unit = {},
    onMoveSectionUp: () -> Unit = {},
    onMoveSectionDown: () -> Unit = {},
    onRemoveSection: () -> Unit = {},
    onChatAbout: (DraftTarget) -> Unit = {},
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
            Text(
                text = state.section.title.ifBlank { "Título do tópico" },
                style = MaterialTheme.typography.titleLarge,
                color = if (state.section.title.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !readOnly) { showTitleDialog = true },
            )
            Spacer(Modifier.height(12.dp))
            if (!readOnly) {
                OutlinedButton(
                    onClick = { onChatAbout(DraftTarget.Section(state.section.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome, null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Conversar sobre este tópico")
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
                        color = MaterialTheme.colorScheme.primary,
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
                                TopicObjectiveField(
                                    objective = state.section.objective,
                                    readOnly = readOnly,
                                    onObjectiveChange = onObjectiveChange,
                                )

                                ReasoningLine(
                                    subPoints = state.subPoints,
                                    readOnly = readOnly,
                                    onUpdateOutlineText = onUpdateSubPointOutlineText,
                                    onMoveSubPoint = onMoveSubPoint,
                                    onRemoveSubPoint = onRemoveSubPoint,
                                    onAddSubPoint = { onAddSubPoint(null) },
                                )

                                TopicSourcesBlock(
                                    sectionId = state.section.id,
                                    refs = topicSources(state.section, state.subPoints),
                                )

                                TopicApproachBlock(
                                    sectionId = state.section.id,
                                    approach = state.section.agreedApproach,
                                    readOnly = readOnly,
                                    onApproachChange = onApproachChange,
                                )

                                MiniSpeechBlock(
                                    sectionId = state.section.id,
                                    contentHtml = state.section.contentHtml,
                                    targetMinutes = state.section.minutes,
                                    readOnly = readOnly,
                                    editorStates = editorStates,
                                    onContentChange = onContentChange,
                                    onSelectionChange = onSelectionChange,
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
                    Spacer(Modifier.weight(1f))
                    if (isBody && state.section.minutes > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
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
}

/** Cabeçalho recolhível com seta à direita (título alinhado à base do card). */
@Composable
private fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 4.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** OBJETIVO — curto e distinto da abordagem. */
@Composable
private fun TopicObjectiveField(
    objective: String?,
    readOnly: Boolean,
    onObjectiveChange: (String) -> Unit,
) {
    var obj by remember(objective) { mutableStateOf(objective.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "OBJETIVO",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = obj,
            onValueChange = {
                obj = it
                onObjectiveChange(it)
            },
            enabled = !readOnly,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                if (obj.isBlank()) {
                    Text(
                        "O que este tópico deve alcançar?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
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

/** FONTES E APOIO — agregado do tópico (recolhível). */
@Composable
private fun TopicSourcesBlock(sectionId: String, refs: List<String>) {
    var expanded by remember(sectionId) { mutableStateOf(false) }
    Column {
        CollapsibleHeader("Fontes e apoio", expanded) { expanded = !expanded }
        if (expanded) {
            Text(
                if (refs.isEmpty()) "Nenhuma referência neste tópico."
                else refs.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** ABORDAGEM ACORDADA — recolhível; distinta do mini discurso. */
@Composable
private fun TopicApproachBlock(
    sectionId: String,
    approach: String?,
    readOnly: Boolean,
    onApproachChange: (String) -> Unit,
) {
    var expanded by remember(sectionId) { mutableStateOf(false) }
    var app by remember(approach) { mutableStateOf(approach.orEmpty()) }
    Column {
        CollapsibleHeader("Abordagem acordada", expanded) { expanded = !expanded }
        if (approach.isNullOrBlank()) {
            Text(
                "A abordagem será registrada quando você chegar a um acordo com o Copilot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            if (readOnly) {
                if (!approach.isNullOrBlank()) {
                    Text(
                        approach,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else {
                OutlinedTextField(
                    value = app,
                    onValueChange = {
                        app = it
                        onApproachChange(it)
                    },
                    placeholder = { Text("A decisão combinada com o Copilot") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (!approach.isNullOrBlank()) {
            Text(
                approach,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    onFocusChange: (Boolean, String) -> Unit,
) {
    val hasText = contentHtml.isNotBlank()
    val miniKey = "section-$sectionId"
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
    val richState = remember(editorKey) {
        editorStates.getOrPut(editorKey) {
            RichTextState().apply { setHtml(initialHtml) }
        }
    }

    LaunchedEffect(editorKey, initialHtml) {
        if (richState.toHtml() != initialHtml) {
            richState.setHtml(initialHtml)
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
                val full = richState.annotatedString.text
                val text = if (sel.collapsed) "" else {
                    val start = sel.start.coerceIn(0, full.length)
                    val end = sel.end.coerceIn(0, full.length)
                    full.substring(start, end).take(2000)
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
        // Discovery #5: richeditor não herda LocalContentColor; sem cor
        // explícita o texto vira preto no dark. cursorBrush idem.
        BasicRichTextEditor(
            state = richState,
            enabled = !readOnly,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = FONT_SIZE_BODY_DEFAULT,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) },
        )
    }
}
