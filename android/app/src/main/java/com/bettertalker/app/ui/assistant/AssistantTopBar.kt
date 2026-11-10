package com.bettertalker.app.ui.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.help.HelpTopBarButton

/**
 * Top bar única do Assistente (v5.1): título vira seletor de contexto;
 * conversa, ajuda e configurações ficam sempre visíveis. Sem ⋮.
 */
@Composable
internal fun AssistantTopBar(
    title: String,
    hasNote: Boolean,
    onOpenChat: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNote: () -> Unit = {},
    onChangeNote: () -> Unit = {},
    onGeneralChat: () -> Unit = {},
    onPickNote: () -> Unit = {},
    onOpenMyOutlines: () -> Unit = {},
    onCreateNote: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenPrepare: () -> Unit = {},
    onOpenTrain: () -> Unit = {},
    onOpenPresent: () -> Unit = {},
    titleStyle: TextStyle,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { menuOpen = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Escolher contexto")
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                assistantMenu(hasNote).forEach { action ->
                    DropdownMenuItem(
                        text = { Text(assistantMenuLabel(action)) },
                        onClick = {
                            menuOpen = false
                            when (action) {
                                AssistantMenuAction.OPEN_NOTE -> onOpenNote()
                                AssistantMenuAction.CHANGE_NOTE -> onChangeNote()
                                AssistantMenuAction.GENERAL_CHAT -> onGeneralChat()
                                AssistantMenuAction.MY_OUTLINES -> onOpenMyOutlines()
                                AssistantMenuAction.CREATE_NOTE -> onCreateNote()
                                AssistantMenuAction.LIBRARY -> onOpenLibrary()
                                AssistantMenuAction.PREPARE -> onOpenPrepare()
                                AssistantMenuAction.TRAIN -> onOpenTrain()
                                AssistantMenuAction.PRESENT -> onOpenPresent()
                            }
                        },
                    )
                }
            }
        }
        IconButton(onClick = onOpenChat) {
            Icon(Icons.Default.Chat, contentDescription = "Abrir conversa")
        }
        HelpTopBarButton(onOpenHelp)
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Configurações")
        }
    }
}
