package com.bettertalker.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bettertalker.app.navigation.BottomTab
import com.bettertalker.app.navigation.activeTab

internal val bottomBarFullLabels = listOf(
    "Assistente",
    "Biblioteca",
    "Preparo",
    "Treino",
    "Palco",
)

internal val bottomBarDestinationIds = listOf(
    "assistant",
    "library",
    "prepare",
    "train",
    "present",
)

internal enum class BottomBarMode { CONVENTIONAL, ADAPTIVE }

/**
 * Rótulos completos só são usados na barra convencional quando há espaço.
 * Em larguras/fontes menores, o seletor adaptativo mantém os nomes completos.
 * Nunca reduz fontScale nem remove a identificação visual.
 */
internal fun bottomBarMode(widthDp: Int, fontScale: Float): BottomBarMode =
    if (widthDp >= 360 && fontScale <= 1.3f) {
        BottomBarMode.CONVENTIONAL
    } else {
        BottomBarMode.ADAPTIVE
    }

private data class AdaptiveDestination(
    val tab: BottomTab,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

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
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints {
        when (bottomBarMode(maxWidth.value.toInt(), fontScale)) {
            BottomBarMode.CONVENTIONAL -> ConventionalBottomBar(
                tab = tab,
                onAssistant = onAssistant,
                onLibrary = onLibrary,
                onPrepare = onPrepare,
                onTrain = onTrain,
                onPresent = onPresent,
            )

            BottomBarMode.ADAPTIVE -> AdaptiveBottomBar(
                tab = tab,
                onAssistant = onAssistant,
                onLibrary = onLibrary,
                onPrepare = onPrepare,
                onTrain = onTrain,
                onPresent = onPresent,
            )
        }
    }
}

@Composable
private fun ConventionalBottomBar(
    tab: BottomTab?,
    onAssistant: () -> Unit,
    onLibrary: () -> Unit,
    onPrepare: () -> Unit,
    onTrain: () -> Unit,
    onPresent: () -> Unit,
) {
    NavigationBar(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .heightIn(min = 72.dp),
        windowInsets = WindowInsets(0),
    ) {
        NavigationBarItem(
            selected = tab == BottomTab.ASSISTANT,
            onClick = onAssistant,
            icon = { Icon(Icons.Default.AutoAwesome, null) },
            label = { Text(bottomBarFullLabels[0]) },
        )
        NavigationBarItem(
            selected = tab == BottomTab.LIBRARY,
            onClick = onLibrary,
            icon = { Icon(Icons.Default.Book, null) },
            label = { Text(bottomBarFullLabels[1]) },
        )
        NavigationBarItem(
            selected = tab == BottomTab.PREPARE,
            onClick = onPrepare,
            icon = { Icon(Icons.Default.EditNote, null) },
            label = { Text(bottomBarFullLabels[2]) },
        )
        NavigationBarItem(
            selected = tab == BottomTab.TRAIN,
            onClick = onTrain,
            icon = { Icon(Icons.Default.RecordVoiceOver, null) },
            label = { Text(bottomBarFullLabels[3]) },
        )
        NavigationBarItem(
            selected = tab == BottomTab.PRESENT,
            onClick = onPresent,
            icon = { Icon(InterpreterMode, null) },
            label = { Text(bottomBarFullLabels[4]) },
        )
    }
}

@Composable
private fun AdaptiveBottomBar(
    tab: BottomTab?,
    onAssistant: () -> Unit,
    onLibrary: () -> Unit,
    onPrepare: () -> Unit,
    onTrain: () -> Unit,
    onPresent: () -> Unit,
) {
    val destinations = listOf(
        AdaptiveDestination(BottomTab.ASSISTANT, bottomBarFullLabels[0], Icons.Default.AutoAwesome, onAssistant),
        AdaptiveDestination(BottomTab.LIBRARY, bottomBarFullLabels[1], Icons.Default.Book, onLibrary),
        AdaptiveDestination(BottomTab.PREPARE, bottomBarFullLabels[2], Icons.Default.EditNote, onPrepare),
        AdaptiveDestination(BottomTab.TRAIN, bottomBarFullLabels[3], Icons.Default.RecordVoiceOver, onTrain),
        AdaptiveDestination(BottomTab.PRESENT, bottomBarFullLabels[4], InterpreterMode, onPresent),
    )
    val current = destinations.firstOrNull { it.tab == tab } ?: destinations[0]
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(current.icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(current.label, maxLines = 2)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            destinations.forEach { destination ->
                DropdownMenuItem(
                    text = { Text(destination.label) },
                    leadingIcon = {
                        if (destination.tab == current.tab) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        destination.onClick()
                    },
                )
            }
        }
    }
}
