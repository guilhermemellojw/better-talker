package com.bettertalker.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bettertalker.app.navigation.BottomTab
import com.bettertalker.app.navigation.activeTab

/**
 * 5.5 — navegação global em 5 abas: Assistente · Biblioteca · Preparo ·
 * Treino · Palco. Meus esboços, editor, mapa mental e ensaio/palco ao vivo
 * são rotas sem barra.
 */
@Composable
fun GlobalBottomBar(
    currentRoute: String?,
    onAssistant: () -> Unit,
    onLibrary: () -> Unit,
    onPrepare: () -> Unit,
    onTrain: () -> Unit,
    onPresent: () -> Unit,
) {
    val tab = activeTab(currentRoute)
    NavigationBar(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(72.dp),
        windowInsets = WindowInsets(0),
    ) {
        NavigationBarItem(
            selected = tab == BottomTab.ASSISTANT,
            onClick = onAssistant,
            icon = { Icon(Icons.Default.AutoAwesome, "Assistente") },
            label = { Text("Assistente") },
        )
        NavigationBarItem(
            selected = tab == BottomTab.LIBRARY,
            onClick = onLibrary,
            icon = { Icon(Icons.Default.Book, "Biblioteca") },
            label = { Text("Biblioteca") },
        )
        NavigationBarItem(
            selected = tab == BottomTab.PREPARE,
            onClick = onPrepare,
            icon = { Icon(Icons.Default.EditNote, "Preparo") },
            label = { Text("Preparo") },
        )
        NavigationBarItem(
            selected = tab == BottomTab.TRAIN,
            onClick = onTrain,
            icon = { Icon(Icons.Default.RecordVoiceOver, "Treino") },
            label = { Text("Treino") },
        )
        NavigationBarItem(
            selected = tab == BottomTab.PRESENT,
            onClick = onPresent,
            icon = { Icon(InterpreterMode, "Palco") },
            label = { Text("Palco") },
        )
    }
}
