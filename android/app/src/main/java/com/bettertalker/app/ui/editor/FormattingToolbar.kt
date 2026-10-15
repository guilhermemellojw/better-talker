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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * #13-fix-a — toolbar de formatação reorganizada (extraída do EditorScreen).
 *
 * - Phone (<600dp): linha fina [B I U S | 🎨 | Aa | ⋮] + ModalBottomSheet
 *   com os blocos (cor/marca-texto, tamanho, texto/listas/alinhamento/mais).
 * - Tablet (≥600dp): linha expandida inline, sem ⋮ (pickles em popup).
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
) {
    val enabled = activeEditorState != null
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
    var tabletPicker by remember { mutableStateOf<TabletPicker?>(null) }
    var sheet by remember { mutableStateOf<SheetMode?>(null) }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

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
    val onUndo: () -> Unit = { history?.undo() }
    val onRedo: () -> Unit = { history?.redo() }

    Column(modifier) {
        if (isTablet) {
            // Tablet: tudo inline (sem ⋮); pickers em popup com grid 48dp.
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
                            active = tabletPicker == TabletPicker.COLORS, enabled = enabled,
                        ) { tabletPicker = TabletPicker.COLORS }
                        DropdownMenu(
                            expanded = tabletPicker == TabletPicker.COLORS,
                            onDismissRequest = { tabletPicker = null },
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SheetTitle("Cor de fonte")
                                ColorGrid(FONT_COLORS, curSpan.color) { onFontColor(it); tabletPicker = null }
                            }
                        }
                    }
                }
                item {
                    Box {
                        ToolBtn(
                            Icons.Default.FormatSize, "Tamanho",
                            active = tabletPicker == TabletPicker.SIZE, enabled = enabled,
                        ) { tabletPicker = TabletPicker.SIZE }
                        DropdownMenu(
                            expanded = tabletPicker == TabletPicker.SIZE,
                            onDismissRequest = { tabletPicker = null },
                        ) {
                            SizeRow(curSpan, onSize) { tabletPicker = null }
                        }
                    }
                }
                item {
                    Box {
                        ToolBtn(
                            Icons.Default.Highlight, "Marca-texto",
                            active = hasHighlight, enabled = enabled,
                        ) { tabletPicker = TabletPicker.HIGHLIGHT }
                        DropdownMenu(
                            expanded = tabletPicker == TabletPicker.HIGHLIGHT,
                            onDismissRequest = { tabletPicker = null },
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SheetTitle("Marca-texto")
                                ColorGrid(HIGHLIGHT_COLORS, curSpan.background) { onHighlight(it); tabletPicker = null }
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
            }
        } else {
            // #13-fix-a2: linha fina FULL-WIDTH — 3 grupos com separadores
            // ([B I U S] │ [🎨 Aa ⬛] │ [⋮]); weights 4/3/1 preenchem a
            // largura toda (sem vazio à direita).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Grupo 1 — texto.
                Row(
                    modifier = Modifier.weight(4f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolBtn(Icons.Default.FormatBold, "Negrito", active = isBold, enabled = enabled, onClick = onBold)
                    ToolBtn(Icons.Default.FormatItalic, "Itálico", active = isItalic, enabled = enabled, onClick = onItalic)
                    ToolBtn(Icons.Default.FormatUnderlined, "Sublinhado", active = isUnderline, enabled = enabled, onClick = onUnderline)
                    ToolBtn(Icons.Default.FormatStrikethrough, "Tachado", active = isStrike, enabled = enabled, onClick = onStrike)
                }
                VerticalDivider(modifier = Modifier.height(24.dp))
                // Grupo 2 — visual (cor de fonte, tamanho, marca-texto).
                Row(
                    modifier = Modifier.weight(3f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolBtn(
                        Icons.Default.FormatColorText, "Cor de fonte",
                        active = sheet == SheetMode.COLORS, enabled = enabled,
                    ) { sheet = SheetMode.COLORS }
                    ToolBtn(
                        Icons.Default.FormatSize, "Tamanho",
                        active = sheet == SheetMode.SIZE, enabled = enabled,
                    ) { sheet = SheetMode.SIZE }
                    ToolBtn(
                        Icons.Default.Highlight, "Marca-texto",
                        // Indicador de estado pelo cursor (background ativo).
                        active = hasHighlight, enabled = enabled,
                    ) { sheet = SheetMode.HIGHLIGHT }
                }
                VerticalDivider(modifier = Modifier.height(24.dp))
                // Grupo 3 — menu.
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ToolBtn(
                        Icons.Default.MoreVert, "Mais opções",
                        active = sheet == SheetMode.MORE, enabled = enabled,
                    ) { sheet = SheetMode.MORE }
                }
            }
        }
    }

    val sheetMode = sheet
    if (sheetMode != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            when (sheetMode) {
                SheetMode.COLORS -> ColorPickerSheetContent(
                    title = "Cor de fonte",
                    colors = FONT_COLORS,
                    current = curSpan.color,
                    onPick = onFontColor,
                    onDismiss = { sheet = null },
                )
                SheetMode.HIGHLIGHT -> ColorPickerSheetContent(
                    title = "Marca-texto",
                    colors = HIGHLIGHT_COLORS,
                    current = curSpan.background,
                    onPick = onHighlight,
                    onDismiss = { sheet = null },
                )
                SheetMode.SIZE -> SizeSheetContent(
                    curSpan = curSpan,
                    onSize = onSize,
                    onDismiss = { sheet = null },
                )
                SheetMode.MORE -> MoreSheetContent(
                    isHeading = isHeading, isBullet = isBullet, isNumbered = isNumbered,
                    align = align, canUndo = canUndo, canRedo = canRedo,
                    onHeading = onHeading, onBullet = onBullet, onNumbered = onNumbered,
                    onAlign = onAlign, onClear = onClear, onUndo = onUndo, onRedo = onRedo,
                    onDismiss = { sheet = null },
                )
            }
        }
    }
}

/** #13-fix-a: pickers do tablet (popup) e do sheet do phone. */
private enum class TabletPicker { COLORS, HIGHLIGHT, SIZE }
private enum class SheetMode { COLORS, HIGHLIGHT, SIZE, MORE }

// Paleta de cores da fonte (estilo Word) — movida do EditorScreen.
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
    onPick: (Color) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (listOf(Color.Unspecified) + colors).chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { c ->
                    val selected = if (c == Color.Unspecified) current == Color.Unspecified
                    else current == c
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onPick(c) },
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

@Composable
private fun ColorPickerSheetContent(
    title: String,
    colors: List<Color>,
    current: Color,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SheetTitle(title)
        ColorGrid(colors, current) { onPick(it); onDismiss() }
    }
}

@Composable
private fun SizeSheetContent(
    curSpan: SpanStyle,
    onSize: (TextUnit) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SheetTitle("Tamanho")
        SizeRow(curSpan, onSize, onDismiss)
    }
}

@Composable
private fun MoreSheetContent(
    isHeading: Boolean,
    isBullet: Boolean,
    isNumbered: Boolean,
    align: TextAlign?,
    canUndo: Boolean,
    canRedo: Boolean,
    onHeading: () -> Unit,
    onBullet: () -> Unit,
    onNumbered: () -> Unit,
    onAlign: (TextAlign) -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SheetTitle("Texto")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetAction("Título", icon = Icons.Default.Title, active = isHeading) {
                onHeading(); onDismiss()
            }
        }
        SheetTitle("Listas")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetAction("Lista", icon = Icons.AutoMirrored.Filled.FormatListBulleted, active = isBullet) {
                onBullet(); onDismiss()
            }
            SheetAction("Numerada", icon = Icons.Default.FormatListNumbered, active = isNumbered) {
                onNumbered(); onDismiss()
            }
        }
        SheetTitle("Alinhamento")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetAction("Esquerda", icon = Icons.AutoMirrored.Filled.FormatAlignLeft, active = align == TextAlign.Left) {
                onAlign(TextAlign.Left); onDismiss()
            }
            SheetAction("Centro", icon = Icons.Default.FormatAlignCenter, active = align == TextAlign.Center) {
                onAlign(TextAlign.Center); onDismiss()
            }
            SheetAction("Direita", icon = Icons.AutoMirrored.Filled.FormatAlignRight, active = align == TextAlign.Right) {
                onAlign(TextAlign.Right); onDismiss()
            }
        }
        SheetTitle("Mais")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetAction("Limpar", icon = Icons.Default.FormatClear) {
                onClear(); onDismiss()
            }
            // Undo/redo NÃO fecham o sheet (uso iterativo).
            SheetAction("Desfazer", icon = Icons.AutoMirrored.Filled.Undo, enabled = canUndo) { onUndo() }
            SheetAction("Refazer", icon = Icons.AutoMirrored.Filled.Redo, enabled = canRedo) { onRedo() }
        }
    }
}

/** Botão de ação do sheet/popup: alvo ≥48dp, ícone + rótulo, ativo em primary. */
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
