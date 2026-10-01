// Editor de uma seção do discurso (INTRO/BODY/CONCLUSION).
package com.bettertalker.app.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
 * Editor de UMA seção do discurso (tarefa 3.2.5b).
 *
 * - INTRO/CONCLUSION: 1 editor rico (`contentHtml`)
 * - BODY: N sub-pontos, cada um com `outlineText` + refs + editor (`developedHtml`)
 *
 * Não inclui toolbar (fica no EditorScreen — decisão A) e não acessa VM:
 * toda comunicação é por callback. O caller decide persistência/autosave.
 *
 * `BasicRichTextEditor` (não o `RichTextEditor` Material3 da lib): é o mesmo
 * componente usado pelo editor atual do app; evita decoração/medidas
 * Material3 que o projeto ainda não usa (ver resposta 1 da 3.2.5b).
 *
 * @param state estado da seção (modelo + sub-pontos + flags)
 * @param isActiveSection destaque visual (seção corrente)
 * @param readOnly modo preview: desabilita campos e editores (tudo só leitura)
 * @param onActivate clique no card (inativa a seção corrente)
 * @param onTitleChange novo título
 * @param onMinutesChange novos minutos
 * @param onContentChange HTML da seção (INTRO/CONCLUSION)
 * @param onSubPointChange (subPointId, html) para BODY
 * @param onActiveEditorChange editor focado: ("section-{id}" | "subpoint-{id}", state)
 *   ou (null, null) quando nenhum — a toolbar global (3.2.5c) opera esse state
 * @param onSelectionChange seleção viva com contexto (3.2.5e; o Copilot consome via VM)
 * @param sectionPendingInserts inserts direcionados a aplicar neste card (3.2.5e)
 * @param onConsumeInsert chamado após aplicar cada insert (3.2.5e)
 * @param isFirstSection true na primeira seção (desabilita "Mover para cima")
 * @param isLastSection true na última seção (desabilita "Mover para baixo")
 * @param onRoleChange troca o papel da seção (menu do chip, 3.2.5f.2b)
 * @param onMoveSectionUp move a seção para cima na ordem (3.2.5f.2b)
 * @param onMoveSectionDown move a seção para baixo na ordem (3.2.5f.2b)
 * @param onRemoveSection remove a seção após confirmação com cascata (3.2.5f.2b)
 *
 * O `RichTextState` exposto em `onActiveEditorChange` é o MESMO objeto vivo
 * guardado no mapa da seção: quando o HTML externo muda, o
 * `LaunchedEffect(initialHtml)` daqui o atualiza in-place — o caller não
 * precisa de novo callback (débito/explicação da decisão 4 da 3.2.5c).
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
    onSubPointChange: (subPointId: String, html: String) -> Unit,
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
    // Fase 3.5e.3: geração de rascunho pelo Copilot.
    canGenerateDraft: Boolean = false,
    onGenerateDraft: (DraftTarget) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Mapa persistente de RichTextStates (key → state). Criado uma vez por
    // seção; nunca reusado entre seções (modelo C'1 do spike 3.2.1b).
    val editorStates = remember(state.section.id) {
        mutableStateMapOf<String, RichTextState>()
    }

    // Guarda de foco (evita limpar a seleção quando o novo editor já assumiu).
    var focusedEditorKey by remember(state.section.id) { mutableStateOf<String?>(null) }

    // 3.2.5f.2b: confirmação local de exclusão; o header só abre o dialog.
    var confirmDelete by remember(state.section.id) { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
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
        Column(Modifier.padding(12.dp)) {
            SectionHeader(
                state = state,
                readOnly = readOnly,
                isFirst = isFirstSection,
                isLast = isLastSection,
                onTitleChange = onTitleChange,
                onMinutesChange = onMinutesChange,
                onRoleChange = onRoleChange,
                onMoveUp = onMoveSectionUp,
                onMoveDown = onMoveSectionDown,
                onRemove = { confirmDelete = true },
                canGenerateDraft = canGenerateDraft,
                onGenerateDraft = onGenerateDraft,
            )
            Spacer(Modifier.height(12.dp))

            // 3.2.5e: aplica inserts pendentes direcionados a esta seção.
            // insertMarkdownAfterSelection usa o cursor do editor; se o editor
            // não estiver focado, a inserção cai no fim do texto (débito
            // documentado — a 3.2.5f pode focar o alvo antes de inserir).
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.subPoints.forEachIndexed { index, subPoint ->
                            // key(id): estado de menu/edição não migra no reorder.
                            key(subPoint.id) {
                                val editorKey = "subpoint-${subPoint.id}"
                                SubPointEditor(
                                    subPoint = subPoint,
                                    sectionId = state.section.id,
                                    isFirst = index == 0,
                                    isLast = index == state.subPoints.lastIndex,
                                    editorStates = editorStates,
                                    readOnly = readOnly,
                                    onSubPointChange = onSubPointChange,
                                    onSelectionChange = onSelectionChange,
                                    onRemove = { onRemoveSubPoint(subPoint.id) },
                                    onMoveUp = { onMoveSubPoint(subPoint.id, MoveDirection.UP) },
                                    onMoveDown = { onMoveSubPoint(subPoint.id, MoveDirection.DOWN) },
                                    onUpdateOutlineText = { newText ->
                                        onUpdateSubPointOutlineText(subPoint.id, newText)
                                    },
                                    canGenerateDraft = canGenerateDraft,
                                    onGenerateDraft = onGenerateDraft,
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
                                        .heightIn(min = 60.dp),
                                )
                            }
                        }
                        // Só em BODY e fora do preview.
                        if (!readOnly) {
                            OutlinedButton(
                                onClick = { onAddSubPoint(null) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            ) {
                                Icon(Icons.Default.Add, "Adicionar ponto")
                                Spacer(Modifier.width(4.dp))
                                Text("Adicionar ponto")
                            }
                        }
                    }
                }
            }
        }
    }

    // 3.2.5f.2b: confirmação com aviso de cascata (FK CASCADE no banco —
    // os sub-pontos da seção vão junto).
    if (confirmDelete) {
        val extra = cascadeWarningMessage(state.subPoints.size)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir seção?") },
            text = { Text("Esta seção será removida.$extra") },
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

@Composable
private fun SectionHeader(
    state: SectionUiState,
    readOnly: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onTitleChange: (String) -> Unit,
    onMinutesChange: (Int) -> Unit,
    onRoleChange: (SectionRole) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    canGenerateDraft: Boolean = false,
    onGenerateDraft: (DraftTarget) -> Unit = {},
) {
    var titleText by remember(state.section.id, state.section.title) {
        mutableStateOf(state.section.title)
    }
    var minutesText by remember(state.section.id, state.section.minutes) {
        mutableStateOf(state.section.minutes.toString())
    }
    var roleMenuOpen by remember(state.section.id) { mutableStateOf(false) }
    var menuOpen by remember(state.section.id) { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. Chip de role → dropdown para trocar (3.2.5f.2b).
        Box {
            AssistChip(
                onClick = { if (!readOnly) roleMenuOpen = true },
                enabled = !readOnly,
                label = { Text(state.section.role.name) },
            )
            DropdownMenu(
                expanded = roleMenuOpen,
                onDismissRequest = { roleMenuOpen = false },
            ) {
                SectionRole.values().forEach { role ->
                    DropdownMenuItem(
                        text = { Text(role.name) },
                        onClick = {
                            onRoleChange(role)
                            roleMenuOpen = false
                        },
                    )
                }
            }
        }
        // 2. Título editável (mantido).
        TextField(
            value = titleText,
            enabled = !readOnly,
            onValueChange = {
                titleText = it
                onTitleChange(it)
            },
            singleLine = true,
            placeholder = { Text("Título da seção") },
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
            ),
        )
        // 3. Minutos editáveis (mantido).
        TextField(
            value = minutesText,
            enabled = !readOnly,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() }.take(2)
                minutesText = filtered
                filtered.toIntOrNull()?.let { onMinutesChange(it) }
            },
            singleLine = true,
            modifier = Modifier.width(76.dp),
            suffix = { Text("min") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
            ),
        )
        // 4. Menu ⋮ da seção: mover/reordenar/excluir (escondido em readOnly).
        if (!readOnly) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "Menu da seção")
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
                    DropdownMenuItem(
                        text = { Text("Excluir seção") },
                        onClick = { onRemove(); menuOpen = false },
                    )
                    // Fase 3.5e.3: gerar rascunho para a seção (só quando
                    // não há sub-pontos — INTRO/CONCLUSION/BODY vazia; em
                    // BODY com sub-pontos o item vive no ⋮ de cada ponto).
                    if (state.subPoints.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Gerar com Copilot") },
                            enabled = canGenerateDraft,
                            onClick = {
                                menuOpen = false
                                onGenerateDraft(DraftTarget.Section(state.section.id))
                            },
                        )
                    }
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
    // State por editor: criado uma vez e mantido no mapa da seção.
    val richState = remember(editorKey) {
        editorStates.getOrPut(editorKey) {
            RichTextState().apply { setHtml(initialHtml) }
        }
    }

    // Reconciliação de escrita externa sem loop: só setHtml se divergir.
    LaunchedEffect(editorKey, initialHtml) {
        if (richState.toHtml() != initialHtml) {
            richState.setHtml(initialHtml)
        }
    }

    // A lib não tem onTextChange: observa a árvore viva (padrão do editor atual).
    LaunchedEffect(editorKey) {
        snapshotFlow { richState.annotatedString }
            .drop(1) // valor inicial
            .debounce(400)
            .collect { onContentChange(richState.toHtml()) }
    }

    // 3.2.5e: seleção viva com contexto (seção/sub-ponto). TextRange não tem
    // `.text`: extrai de annotatedString via start/end (padrão do editor legado).
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
            modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) },
        )
    }
}

@Composable
private fun SubPointEditor(
    subPoint: SubPoint,
    sectionId: String,
    isFirst: Boolean,
    isLast: Boolean,
    editorStates: MutableMap<String, RichTextState>,
    readOnly: Boolean,
    onSubPointChange: (String, String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSelectionChange: (SelectionContext?) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUpdateOutlineText: (String) -> Unit,
    canGenerateDraft: Boolean = false,
    onGenerateDraft: (DraftTarget) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val editorKey = "subpoint-${subPoint.id}"
    // Chave só no id (não no texto): o draft sobrevive ao autosave remoto.
    var menuOpen by remember(subPoint.id) { mutableStateOf(false) }
    var editingOutline by remember(subPoint.id) { mutableStateOf(false) }
    var outlineDraft by remember(subPoint.id) { mutableStateOf(subPoint.outlineText) }
    var confirmDelete by remember(subPoint.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (editingOutline) {
                TextField(
                    value = outlineDraft,
                    onValueChange = { outlineDraft = it },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("Ponto principal") },
                )
                IconButton(onClick = {
                    onUpdateOutlineText(outlineDraft)
                    editingOutline = false
                }) { Icon(Icons.Default.Check, "Salvar") }
                IconButton(onClick = {
                    outlineDraft = subPoint.outlineText
                    editingOutline = false
                }) { Icon(Icons.Default.Close, "Cancelar") }
            } else {
                Text(
                    text = subPoint.outlineText.ifBlank { "(sem texto)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!readOnly) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Menu do ponto")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Editar texto principal") },
                                onClick = {
                                    outlineDraft = subPoint.outlineText
                                    editingOutline = true
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
                                text = { Text("Excluir ponto") },
                                onClick = { confirmDelete = true; menuOpen = false },
                            )
                            // Fase 3.5e.3: gera rascunho para ESTE sub-ponto.
                            DropdownMenuItem(
                                text = { Text("Gerar com Copilot") },
                                enabled = canGenerateDraft,
                                onClick = {
                                    menuOpen = false
                                    onGenerateDraft(DraftTarget.SubPoint(sectionId, subPoint.id))
                                },
                            )
                        }
                    }
                }
            }
        }
        if (subPoint.bibleRefs.isNotEmpty() || subPoint.publicationRefs.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                subPoint.bibleRefs.forEach { ref ->
                    AssistChip(
                        onClick = {},
                        label = { Text(ref, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                subPoint.publicationRefs.forEach { ref ->
                    AssistChip(
                        onClick = {},
                        label = { Text(ref.symbol, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        subPoint.instruction?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionContentEditor(
            editorKey = editorKey,
            initialHtml = subPoint.developedHtml,
            sectionId = sectionId,
            subPointId = subPoint.id,
            editorStates = editorStates,
            readOnly = readOnly,
            onContentChange = { html -> onSubPointChange(subPoint.id, html) },
            onFocusChange = onFocusChange,
            onSelectionChange = onSelectionChange,
            modifier = modifier,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir ponto?") },
            text = { Text("O conteúdo desenvolvido será perdido.") },
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
