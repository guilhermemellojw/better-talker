package com.bettertalker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.util.previewOf
import com.bettertalker.app.ui.home.HomeViewMode
import com.bettertalker.app.ui.home.viewModeLabel

/**
 * ⋮ das notas (card e linhas): Fixar/Desafixar, Mover para pasta e Excluir
 * (cor de erro). Extraído do NoteCard para reuso nos modos de lista.
 */
@Composable
fun NoteOverflowMenu(
    pinned: Boolean,
    onTogglePin: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    if (onDelete == null && onTogglePin == null && onMove == null) return
    val showMenu = remember { mutableStateOf(false) }
    IconButton(onClick = { showMenu.value = true }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Default.MoreVert, "Opções", modifier = Modifier.size(20.dp))
    }
    DropdownMenu(expanded = showMenu.value, onDismissRequest = { showMenu.value = false }) {
        if (onTogglePin != null) {
            DropdownMenuItem(
                text = { Text(if (pinned) "Desafixar" else "Fixar") },
                onClick = { showMenu.value = false; onTogglePin() },
            )
        }
        if (onMove != null) {
            DropdownMenuItem(
                text = { Text("Mover para pasta") },
                onClick = { showMenu.value = false; onMove() },
            )
        }
        if (onDelete != null) {
            DropdownMenuItem(
                text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                onClick = { showMenu.value = false; onDelete() },
            )
        }
    }
}

/** Pill do tipo da nota ("Demonstração"/"Discurso") — hubs e listas. */
@Composable
internal fun NoteBadgePill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Ícone do modo de visualização. Puro. */
internal fun viewModeIcon(m: HomeViewMode) = when (m) {
    HomeViewMode.GRID -> Icons.Default.GridView
    HomeViewMode.LIST_COMPACT -> Icons.Default.ViewList
    HomeViewMode.LIST_DETAILED -> Icons.Default.Article
}

/**
 * Menu dos modos de visualização (Top Bar): ícone do modo atual + dropdown
 * com os 3 (Grade / Lista compacta / Detalhada). Compartilhado Home/hubs.
 */
@Composable
fun ViewModeMenu(
    viewMode: HomeViewMode,
    onSelect: (HomeViewMode) -> Unit,
) {
    val showMenu = remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu.value = true }) {
            Icon(viewModeIcon(viewMode), "Modo de visualização")
        }
        DropdownMenu(
            expanded = showMenu.value,
            onDismissRequest = { showMenu.value = false },
        ) {
            HomeViewMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(viewModeLabel(m)) },
                    leadingIcon = { Icon(viewModeIcon(m), null) },
                    onClick = { showMenu.value = false; onSelect(m) },
                )
            }
        }
    }
}

/** Data curta da nota (mesmo formato do card). */
private fun noteDate(updatedAt: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
        .format(java.util.Date(updatedAt))

/** Cor da nota com fallback transparente. */
private fun noteTint(colorArgb: Long): Color =
    if (colorArgb != 0L) {
        runCatching { Color(colorArgb.toULong()) }.getOrDefault(Color.Transparent)
    } else {
        Color.Transparent
    }

/** Linha compacta (LIST_COMPACT): dot de cor + título + data + ⋮. */
@Composable
fun NoteRowCompact(
    title: String,
    updatedAt: Long,
    pinned: Boolean,
    colorArgb: Long,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onTogglePin: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    /** Hubs: tipo da nota ("Demonstração"/"Discurso") — null = não mostra. */
    badgeText: String? = null,
    /** Hubs: duração ("12 min") — null = não mostra. */
    durationText: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    noteTint(colorArgb).takeIf { it != Color.Transparent }
                        ?: MaterialTheme.colorScheme.outline
                )
        )
        Spacer(Modifier.width(12.dp))
        if (pinned) Text("📌 ", style = MaterialTheme.typography.labelSmall)
        Text(
            title.ifBlank { "Sem título" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badgeText != null || durationText != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 6.dp),
            ) {
                badgeText?.let { NoteBadgePill(it) }
                durationText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            noteDate(updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NoteOverflowMenu(pinned, onTogglePin, onMove, onDelete)
    }
}

/** Linha detalhada (LIST_DETAILED): strip lateral + título + preview + data + ⋮. */
@Composable
fun NoteRowDetailed(
    title: String,
    md: String,
    updatedAt: Long,
    pinned: Boolean,
    colorArgb: Long,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onTogglePin: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    /** Hubs: tipo da nota ("Demonstração"/"Discurso") — null = não mostra. */
    badgeText: String? = null,
    /** Hubs: duração ("12 min") — null = não mostra. */
    durationText: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable { onClick() },
    ) {
        val tint = noteTint(colorArgb)
        if (tint != Color.Transparent) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(tint)
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) Text("📌 ", style = MaterialTheme.typography.labelSmall)
                Text(
                    title.ifBlank { "Sem título" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                NoteOverflowMenu(pinned, onTogglePin, onMove, onDelete)
            }
            if (badgeText != null || durationText != null) {
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    badgeText?.let { NoteBadgePill(it) }
                    durationText?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                previewOf(md),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                noteDate(updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Despachante do item da nota conforme o modo escolhido na Home. */
@Composable
fun NoteItem(
    note: com.bettertalker.app.data.db.NoteEntity,
    mode: HomeViewMode,
    attachCount: Int,
    onClick: () -> Unit,
    onTogglePin: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Hubs: tipo da nota ("Demonstração"/"Discurso") — null = não mostra. */
    badgeText: String? = null,
    /** Hubs: duração ("12 min") — null = não mostra. */
    durationText: String? = null,
) {
    when (mode) {
        HomeViewMode.GRID -> NoteCard(
            title = note.title, md = note.mdText, updatedAt = note.updatedAt,
            pinned = note.pinned, attachCount = attachCount, colorArgb = note.colorArgb,
            onClick = onClick, onDelete = onDelete, onTogglePin = onTogglePin, onMove = onMove,
            badgeText = badgeText, durationText = durationText,
        )
        HomeViewMode.LIST_COMPACT -> NoteRowCompact(
            title = note.title, updatedAt = note.updatedAt, pinned = note.pinned,
            colorArgb = note.colorArgb, onClick = onClick,
            onDelete = onDelete, onTogglePin = onTogglePin, onMove = onMove,
            badgeText = badgeText, durationText = durationText,
        )
        HomeViewMode.LIST_DETAILED -> NoteRowDetailed(
            title = note.title, md = note.mdText, updatedAt = note.updatedAt,
            pinned = note.pinned, colorArgb = note.colorArgb, onClick = onClick,
            onDelete = onDelete, onTogglePin = onTogglePin, onMove = onMove,
            badgeText = badgeText, durationText = durationText,
        )
    }
}
