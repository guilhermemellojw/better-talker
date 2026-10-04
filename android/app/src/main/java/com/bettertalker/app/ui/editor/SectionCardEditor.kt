// Editor de uma seção do discurso (INTRO/BODY/CONCLUSION).
package com.bettertalker.app.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
internal fun roleChipColors(role: SectionRole): Color = when (role) {
    SectionRole.INTRO -> MaterialTheme.colorScheme.primaryContainer
    SectionRole.BODY -> MaterialTheme.colorScheme.secondaryContainer
    SectionRole.CONCLUSION -> MaterialTheme.colorScheme.tertiaryContainer
}

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
    canGenerateDraft: Boolean = false,
    onGenerateDraft: (DraftTarget) -> Unit = {},
    onChatAbout: (DraftTarget) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val editorStates = remember(state.section.id) {
        mutableStateMapOf<String, RichTextState>()
    }
    var focusedEditorKey by remember(state.section.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(state.section.id) { mutableStateOf(false) }

    val isBody = state.section.role == SectionRole.BODY

    var titleText by remember(state.section.id, state.section.title) {
        mutableStateOf(state.section.title)
    }
    var minutesText by remember(state.section.id, state.section.minutes) {
        mutableStateOf(state.section.minutes.toString())
    }
    var roleMenuOpen by remember(state.section.id) { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(start = 20.dp, end = 8.dp, bottom = 2.dp)
                .then(
                    if (isBody && !isActiveSection) {
                        Modifier.clickable { onActivate() }
                    } else Modifier
                ),
        ) {
            if (isBody) {
                TextField(
                    value = titleText,
                    enabled = !readOnly,
                    onValueChange = {
                        // Título é uma linha lógica: quebra de linha vira espaço.
                        val v = it.replace("\n", " ")
                        titleText = v
                        onTitleChange(v)
                    },
                    // Multi-linha para o usuário ler o tópico inteiro sem rolar.
                    singleLine = false,
                    maxLines = 3,
                    placeholder = {
                        Text(
                            "Título do tópico",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                TextField(
                    value = minutesText,
                    enabled = !readOnly,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }.take(2)
                        minutesText = filtered
                        filtered.toIntOrNull()?.let { onMinutesChange(it) }
                    },
                    singleLine = true,
                    modifier = Modifier.width(60.dp),
                    suffix = { Text("min") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                SectionMenuButton(
                    sectionId = state.section.id,
                    readOnly = readOnly,
                    isFirst = isFirstSection,
                    isLast = isLastSection,
                    showRoleChange = true,
                    primaryActionsOnCard = true,
                    onRoleChange = onRoleChange,
                    onMoveUp = onMoveSectionUp,
                    onMoveDown = onMoveSectionDown,
                    onRemove = { confirmDelete = true },
                    onGenerateDraft = onGenerateDraft,
                    onChatAbout = onChatAbout,
                )
            } else {
                Box {
                    Surface(
                        onClick = { if (!readOnly) roleMenuOpen = true },
                        enabled = !readOnly,
                        shape = RoundedCornerShape(4.dp),
                        color = roleChipColors(state.section.role),
                    ) {
                        Text(
                            friendlyRoleName(state.section.role).uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
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
            Box {
                Column(
                    Modifier.padding(
                        start = 12.dp,
                        end = if (isBody) 4.dp else 32.dp,
                        top = 8.dp, bottom = 10.dp,
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
                                    readOnly = readOnly,
                                    canGenerateDraft = canGenerateDraft,
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
                                    onGenerateDraft = {
                                        onGenerateDraft(DraftTarget.Section(state.section.id))
                                    },
                                )
                            }
                        }
                    }
                }

                if (!isBody) {
                    SectionMenuButton(
                        sectionId = state.section.id,
                        readOnly = readOnly,
                        isFirst = isFirstSection,
                        isLast = isLastSection,
                        showRoleChange = false,
                        primaryActionsOnCard = false,
                        onRoleChange = onRoleChange,
                        onMoveUp = onMoveSectionUp,
                        onMoveDown = onMoveSectionDown,
                        onRemove = { confirmDelete = true },
                        onGenerateDraft = onGenerateDraft,
                        onChatAbout = onChatAbout,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp),
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
}

/** Cabeçalho recolhível com seta à direita (título alinhado à base do card). */
@Composable
private fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
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
            color = MaterialTheme.colorScheme.primary,
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
    readOnly: Boolean,
    canGenerateDraft: Boolean,
    editorStates: MutableMap<String, RichTextState>,
    onContentChange: (String) -> Unit,
    onSelectionChange: (SelectionContext?) -> Unit,
    onFocusChange: (Boolean, String) -> Unit,
    onGenerateDraft: () -> Unit,
) {
    val hasText = contentHtml.isNotBlank()
    val miniKey = "section-$sectionId"
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "MINI DISCURSO",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
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
        if (!readOnly) {
            Button(
                onClick = onGenerateDraft,
                enabled = canGenerateDraft,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (hasText) "Atualizar mini discurso" else "Criar mini discurso")
            }
            if (!canGenerateDraft) {
                Text(
                    "Configure um modelo de IA na tela Modelo IA para gerar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionMenuButton(
    sectionId: String,
    readOnly: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    showRoleChange: Boolean = false,
    // F3.1: em BODY as ações primárias vivem no card; o ⋮ fica só com o
    // estrutural. Em INTRO/CONCLUSÃO o ⋮ ainda expõe conversar/gerar.
    primaryActionsOnCard: Boolean = false,
    onRoleChange: (SectionRole) -> Unit = {},
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onGenerateDraft: (DraftTarget) -> Unit,
    onChatAbout: (DraftTarget) -> Unit,
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
            if (!primaryActionsOnCard) {
                DropdownMenuItem(
                    text = { Text("Conversar sobre esta parte") },
                    onClick = {
                        menuOpen = false
                        onChatAbout(DraftTarget.Section(sectionId))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Gerar com Copilot") },
                    onClick = {
                        menuOpen = false
                        onGenerateDraft(DraftTarget.Section(sectionId))
                    },
                )
            }
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
        BasicRichTextEditor(
            state = richState,
            enabled = !readOnly,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = FONT_SIZE_BODY_DEFAULT,
            ),
            modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) },
        )
    }
}
