package com.bettertalker.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
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
    NavigationBar(
        // Barra compacta: 72dp de CONTEÚDO + a área de gestos do sistema por
        // fora (o inset não infla a barra — nada de barra "gorda").
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(72.dp),
        windowInsets = WindowInsets(0),
    ) {
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
