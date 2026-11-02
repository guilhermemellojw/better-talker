package com.bettertalker.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * #13-fix-a — toolbar de formatação reorganizada (extraída do EditorScreen).
 *
 * - Uma única linha horizontal com todos os controles (scroll horizontal),
 *   igual em phone/tablet; pickers em popup. Sem menu “⋮”.
 * - Pickers em grid com hitbox de 48dp; fecham ao escolher (bug do sticky).
 * - Indicadores de estado: B/I/U/S + título + listas + alinhamento.
 * - Undo/redo expostos via RichTextState.getHistory().
 *
 * A LÓGICA das ações é a mesma de antes (mesmos callbacks do RichTextState).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorFormattingToolbar(
    activeEditorState: RichTextState?,
    modifier: Modifier = Modifier,
    /** Card-first: seleção viva → card (sem perder o alvo ao navegar). */
    onTransformCard: (() -> Unit)? = null,
    canTransformCard: Boolean = false,
) {
    val enabled = activeEditorState != null
    // Block 2: cores de fonte conforme o modo (preto → onSurface no escuro).
    val darkSurface = com.bettertalker.app.ui.theme.relativeLuminance(
        MaterialTheme.colorScheme.surface
    ) < 0.5
    val fontColorList = fontColors(darkSurface, MaterialTheme.colorScheme.onSurface)
    val curSpan = activeEditorState?.currentSpanStyle ?: SpanStyle()
    val isBold = (curSpan.fontWeight?.weight ?: 400) > 400
    val isItalic = curSpan.fontStyle == FontStyle.Italic
    val isUnderline = curSpan.textDecoration?.contains(TextDecoration.Underline) == true
    val isStrike = curSpan.textDecoration?.contains(TextDecoration.LineThrough) == true
    // #13-fix-a (F): indicadores de estado além de B/I/U/S.
    val isHeading = activeEditorState?.currentHeadingStyle?.let { it != HeadingStyle.Normal } == true
    val isBullet = activeEditorState?.isUnorderedList == true
    val isNumbered = activeEditorState?.isOrderedList == true
    val align = activeEditorState?.currentParagraphStyle?.textAlign
    // #13-fix-a (E): undo/redo da lib (RichTextHistory) — já existe, só não exposto.
    val history = activeEditorState?.history
    val canUndo = history?.canUndo == true
    val canRedo = history?.canRedo == true

    // #13-fix-a2: indicador do marca-texto — background ativo no cursor.
    val hasHighlight = curSpan.background != Color.Unspecified
    var picker by remember { mutableStateOf<ToolbarPicker?>(null) }

    // Mesmos callbacks de antes (#13-fix-a não muda a lógica).
    val onBold: () -> Unit = { activeEditorState?.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
    val onItalic: () -> Unit = { activeEditorState?.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
    val onUnderline: () -> Unit = { activeEditorState?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
    val onStrike: () -> Unit = { activeEditorState?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
    val onFontColor: (Color) -> Unit = { activeEditorState?.toggleSpanStyle(SpanStyle(color = it)) }
    val onHighlight: (Color) -> Unit = { activeEditorState?.toggleSpanStyle(SpanStyle(background = it)) }
    val onSize: (TextUnit) -> Unit = { activeEditorState?.toggleSpanStyle(SpanStyle(fontSize = it)) }
    val onHeading: () -> Unit = { activeEditorState?.insertMarkdownAfterSelection("\n# ") }
    val onBullet: () -> Unit = { activeEditorState?.toggleUnorderedList() }
    val onNumbered: () -> Unit = { activeEditorState?.toggleOrderedList() }
    val onAlign: (TextAlign) -> Unit = { activeEditorState?.toggleParagraphStyle(ParagraphStyle(textAlign = it)) }
    val onClear: () -> Unit = {
        activeEditorState?.toggleSpanStyle(
            SpanStyle(
                color = Color.Unspecified,
                background = Color.Unspecified,
                fontWeight = FontWeight.Normal,
                fontStyle = FontStyle.Normal,
                textDecoration = TextDecoration.None,
                fontSize = TextUnit.Unspecified,
            )
        )
    }
    val onUndo: () -> Unit = {
        runCatching { history?.undo() }
            .onFailure { android.util.Log.e("ToolbarCrash", "Erro no Undo", it) }
    }
    val onRedo: () -> Unit = {
        runCatching { history?.redo() }
            .onFailure { android.util.Log.e("ToolbarCrash", "Erro no Redo", it) }
    }

    Column(modifier) {
        // Uma única linha com todos os controles, rolável na horizontal.
        // Mesmo comportamento em phone/tablet; pickers em popup. Sem “⋮”.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(end = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { ToolBtn(Icons.Default.FormatBold, "Negrito", active = isBold, enabled = enabled, onClick = onBold) }
            item { ToolBtn(Icons.Default.FormatItalic, "Itálico", active = isItalic, enabled = enabled, onClick = onItalic) }
            item { ToolBtn(Icons.Default.FormatUnderlined, "Sublinhado", active = isUnderline, enabled = enabled, onClick = onUnderline) }
            item { ToolBtn(Icons.Default.FormatStrikethrough, "Tachado", active = isStrike, enabled = enabled, onClick = onStrike) }
            item {
                Box {
                    ToolBtn(
                        Icons.Default.FormatColorText, "Cor de fonte",
                        active = picker == ToolbarPicker.COLORS, enabled = enabled,
                    ) { picker = ToolbarPicker.COLORS }
                    DropdownMenu(
                        expanded = picker == ToolbarPicker.COLORS,
                        onDismissRequest = { picker = null },
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SheetTitle("Cor de fonte")
                            ColorGrid(fontColorList, curSpan.color, FONT_COLOR_NAMES) {
                                onFontColor(it); picker = null
                            }
                        }
                    }
                }
            }
            item {
                Box {
                    ToolBtn(
                        Icons.Default.FormatSize, "Tamanho",
                        active = picker == ToolbarPicker.SIZE, enabled = enabled,
                    ) { picker = ToolbarPicker.SIZE }
                    DropdownMenu(
                        expanded = picker == ToolbarPicker.SIZE,
                        onDismissRequest = { picker = null },
                    ) {
                        SizeRow(curSpan, onSize) { picker = null }
                    }
                }
            }
            item {
                Box {
                    ToolBtn(
                        Icons.Default.Highlight, "Marca-texto",
                        active = hasHighlight, enabled = enabled,
                    ) { picker = ToolbarPicker.HIGHLIGHT }
                    DropdownMenu(
                        expanded = picker == ToolbarPicker.HIGHLIGHT,
                        onDismissRequest = { picker = null },
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SheetTitle("Marca-texto")
                            ColorGrid(
                                HIGHLIGHT_COLORS,
                                curSpan.background,
                                HIGHLIGHT_COLOR_NAMES,
                            ) { onHighlight(it); picker = null }
                        }
                    }
                }
            }
            item { ToolBtn(Icons.Default.Title, "Título", active = isHeading, enabled = enabled, onClick = onHeading) }
            item { ToolBtn(Icons.AutoMirrored.Filled.FormatListBulleted, "Lista", active = isBullet, enabled = enabled, onClick = onBullet) }
            item { ToolBtn(Icons.Default.FormatListNumbered, "Lista numerada", active = isNumbered, enabled = enabled, onClick = onNumbered) }
            item { ToolBtn(Icons.AutoMirrored.Filled.FormatAlignLeft, "Alinhar à esquerda", active = align == TextAlign.Left, enabled = enabled) { onAlign(TextAlign.Left) } }
            item { ToolBtn(Icons.Default.FormatAlignCenter, "Centralizar", active = align == TextAlign.Center, enabled = enabled) { onAlign(TextAlign.Center) } }
            item { ToolBtn(Icons.AutoMirrored.Filled.FormatAlignRight, "Alinhar à direita", active = align == TextAlign.Right, enabled = enabled) { onAlign(TextAlign.Right) } }
            item { ToolBtn(Icons.Default.FormatClear, "Limpar formatação", enabled = enabled, onClick = onClear) }
            item { ToolBtn(Icons.AutoMirrored.Filled.Undo, "Desfazer", enabled = enabled && canUndo, onClick = onUndo) }
            item { ToolBtn(Icons.AutoMirrored.Filled.Redo, "Refazer", enabled = enabled && canRedo, onClick = onRedo) }
            if (canTransformCard && onTransformCard != null) {
                item {
                    ToolBtn(
                        Icons.Default.Create,
                        "Transformar em card",
                        enabled = enabled,
                        onClick = onTransformCard,
                    )
                }
            }
        }
    }

}
/** Pickers inline (popup) da barra. */
private enum class ToolbarPicker { COLORS, HIGHLIGHT, SIZE }

// Paleta de cores da fonte (estilo Word) — movida do EditorScreen.
// Block 2: no modo escuro o "preto" vira onSurface (legível no fundo escuro).
internal fun fontColors(dark: Boolean, onSurface: Color = Color.Black): List<Color> = listOf(
    Color(0xFFD32F2F), Color(0xFFF57C00), Color(0xFFFBC02D),
    Color(0xFF388E3C), Color(0xFF1976D2), Color(0xFF7B1FA2),
    if (dark) onSurface else Color.Black
)
private val FONT_COLOR_NAMES = listOf(
    "Vermelho", "Laranja", "Amarelo", "Verde", "Azul", "Roxo", "Preto",
)
// Cores de marca-texto
private val HIGHLIGHT_COLORS = listOf(
    Color(0xFFFFFF00), Color(0xFFA5FF8A), Color(0xFF8AD4FF),
    Color(0xFFFFB3BA), Color(0xFFFFE08A)
)
private val HIGHLIGHT_COLOR_NAMES = listOf(
    "Amarelo", "Verde", "Azul", "Rosa", "Laranja",
)

@Composable
private fun ToolBtn(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
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
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

/**
 * #13-fix-a (D): grid de cores com hitbox de 48dp por dot (antes 24-30dp),
 * indicador do selecionado e "sem cor" primeiro. O fechamento do picker é
 * responsabilidade do chamador (onPick já fecha — corrige o sticky).
 */
@Composable
private fun ColorGrid(
    colors: List<Color>,
    current: Color,
    names: List<String> = emptyList(),
    onPick: (Color) -> Unit,
) {
    val all = listOf(Color.Unspecified) + colors
    val allNames = listOf("Sem cor") + names
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        all.withIndex().chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (index, c) ->
                    val selected = if (c == Color.Unspecified) current == Color.Unspecified
                    else current == c
                    val name = allNames.getOrElse(index) { "Cor ${index + 1}" }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onPick(c) },
                            )
                            .semantics {
                                contentDescription = name
                                stateDescription = if (selected) {
                                    "Selecionada"
                                } else {
                                    "Não selecionada"
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
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
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c == Color.Unspecified) {
                                Icon(
                                    Icons.Default.FormatClear, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SizeRow(
    curSpan: SpanStyle,
    onSize: (TextUnit) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "Pequeno" to FONT_SIZE_S,
            "Médio" to FONT_SIZE_M,
            "Grande" to FONT_SIZE_G,
        ).forEach { (label, size) ->
            SheetAction(label, active = curSpan.fontSize == size) {
                onSize(size)
                onDismiss()
            }
        }
    }
}

/** Botão de ação do popup: alvo ≥48dp, ícone + rótulo, ativo em primary. */
@Composable
private fun SheetAction(
    label: String,
    icon: ImageVector? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        colors = if (active) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label)
    }
}
