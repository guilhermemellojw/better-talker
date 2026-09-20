package com.bettertalker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.util.previewOf
import java.text.DateFormat
import java.util.Date

@Composable
fun ByodNotice(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Uso pessoal (BYOD)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Baixe você mesmo do jw.org. O app só lê o arquivo localmente para sugerir ideias — não copia nem compartilha publicações.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun NoteCard(
    title: String,
    md: String,
    updatedAt: Long,
    pinned: Boolean,
    attachCount: Int,
    colorArgb: Long,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tint = if (colorArgb != 0L) {
        runCatching { Color(colorArgb.toULong()) }.getOrDefault(Color.Transparent)
    } else Color.Transparent
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            if (tint != Color.Transparent) {
                Box(
                    Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .background(tint)
                )
            }
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (pinned) Text("📌 Fixada  ", style = MaterialTheme.typography.labelSmall)
                    Text(
                        title.ifBlank { "Sem título" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    if (onDelete != null) {
                        val showMenu = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        IconButton(onClick = { showMenu.value = true }) {
                            Icon(Icons.Default.MoreVert, "Opções")
                        }
                        DropdownMenu(
                            expanded = showMenu.value,
                            onDismissRequest = { showMenu.value = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Excluir") },
                                onClick = { showMenu.value = false; onDelete() }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(previewOf(md), style = MaterialTheme.typography.bodySmall, maxLines = 4)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(updatedAt))} • $attachCount anexo(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
