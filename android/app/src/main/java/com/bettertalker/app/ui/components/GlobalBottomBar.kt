package com.bettertalker.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TheaterComedy
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
 * Navegação global (Assistente · Esboços · Treino · Palco). Visível no
 * Assistente, em Esboços, nos hubs e na Tribuna (o Treino é imersivo — a
 * barra some). Treino/Palco abrem o esboço atual do modo (com "Trocar
 * esboço" quando necessário).
 */
@Composable
fun GlobalBottomBar(
    currentRoute: String?,
    onAssistant: () -> Unit,
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
            selected = tab == BottomTab.ASSISTANT,
            onClick = onAssistant,
            icon = { Icon(Icons.Default.AutoAwesome, "Assistente") },
            label = { Text("Assistente") },
        )
        NavigationBarItem(
            selected = tab == BottomTab.HOME,
            onClick = onHome,
            icon = { Icon(Icons.Default.LibraryBooks, "Esboços") },
            label = { Text("Esboços") },
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
            icon = { Icon(Icons.Default.TheaterComedy, "Palco") },
            label = { Text("Palco") },
        )
    }
}
