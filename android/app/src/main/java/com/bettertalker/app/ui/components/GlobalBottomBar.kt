package com.bettertalker.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bettertalker.app.navigation.BottomTab
import com.bettertalker.app.navigation.activeTab

/**
 * Navegação global (HOME · TREINAR · APRESENTAR). Visível na Home, nos hubs
 * e na Tribuna (o Ensaio é imersivo — a barra some). Treinar/Apresentar abrem
 * os hubs de escolha visual da nota (nada de "última nota" silenciosa).
 */
@Composable
fun GlobalBottomBar(
    currentRoute: String?,
    onHome: () -> Unit,
    onTrain: () -> Unit,
    onPresent: () -> Unit,
) {
    val tab = activeTab(currentRoute)
    NavigationBar {
        NavigationBarItem(
            selected = tab == BottomTab.HOME,
            onClick = onHome,
            icon = { Icon(Icons.Default.Home, "Home") },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = tab == BottomTab.TRAIN,
            onClick = onTrain,
            icon = { Icon(Icons.Default.RecordVoiceOver, "Treinar") },
            label = { Text("Treinar") },
        )
        NavigationBarItem(
            selected = tab == BottomTab.PRESENT,
            onClick = onPresent,
            icon = { Icon(Icons.Default.MenuBook, "Apresentar") },
            label = { Text("Apresentar") },
        )
    }
}
