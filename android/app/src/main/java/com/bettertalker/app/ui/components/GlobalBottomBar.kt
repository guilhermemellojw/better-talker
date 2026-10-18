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
import com.bettertalker.app.navigation.Routes

/**
 * Navegação global (HOME · TREINAR · APRESENTAR). Visível só na Home e na
 * Tribuna (o Ensaio é imersivo — a barra some); Treinar/Apresentar abrem a
 * última nota (`last_note_id`) ou o sheet de escolha quando não há nenhuma.
 */
@Composable
fun GlobalBottomBar(
    currentRoute: String?,
    onHome: () -> Unit,
    onTrain: () -> Unit,
    onPresent: () -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.HOME,
            onClick = onHome,
            icon = { Icon(Icons.Default.Home, "Home") },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.STAGE,
            onClick = onTrain,
            icon = { Icon(Icons.Default.RecordVoiceOver, "Treinar") },
            label = { Text("Treinar") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.TRIBUNA,
            onClick = onPresent,
            icon = { Icon(Icons.Default.MenuBook, "Apresentar") },
            label = { Text("Apresentar") },
        )
    }
}
