package com.bettertalker.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
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

internal data class BottomBarLayout(
    val labels: List<String>,
    val showLabels: Boolean,
)

/** Política pura de rótulos: nunca reduz fonte artificialmente. */
internal fun bottomBarLayout(widthDp: Int, fontScale: Float): BottomBarLayout {
    val compact = widthDp <= 360 || fontScale >= 1.3f
    val iconOnly = widthDp <= 360 && fontScale >= 1.5f
    val labels = if (compact) {
        listOf("IA", "Bib.", "Prep.", "Treino", "Palco")
    } else {
        bottomBarFullLabels
    }
    return BottomBarLayout(labels = labels, showLabels = !iconOnly)
}

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
        val layout = bottomBarLayout(maxWidth.value.toInt(), fontScale)
        val iconDescription: @Composable (String) -> String? = { label ->
            if (layout.showLabels) null else label
        }
        NavigationBar(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 72.dp),
            windowInsets = WindowInsets(0),
        ) {
            NavigationBarItem(
                selected = tab == BottomTab.ASSISTANT,
                onClick = onAssistant,
                icon = {
                    Icon(
                        Icons.Default.AutoAwesome,
                        iconDescription(bottomBarFullLabels[0]),
                    )
                },
                label = if (layout.showLabels) {
                    { Text(layout.labels[0], maxLines = 1) }
                } else null,
            )
            NavigationBarItem(
                selected = tab == BottomTab.LIBRARY,
                onClick = onLibrary,
                icon = {
                    Icon(
                        Icons.Default.Book,
                        iconDescription(bottomBarFullLabels[1]),
                    )
                },
                label = if (layout.showLabels) {
                    { Text(layout.labels[1], maxLines = 1) }
                } else null,
            )
            NavigationBarItem(
                selected = tab == BottomTab.PREPARE,
                onClick = onPrepare,
                icon = {
                    Icon(
                        Icons.Default.EditNote,
                        iconDescription(bottomBarFullLabels[2]),
                    )
                },
                label = if (layout.showLabels) {
                    { Text(layout.labels[2], maxLines = 1) }
                } else null,
            )
            NavigationBarItem(
                selected = tab == BottomTab.TRAIN,
                onClick = onTrain,
                icon = {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        iconDescription(bottomBarFullLabels[3]),
                    )
                },
                label = if (layout.showLabels) {
                    { Text(layout.labels[3], maxLines = 1) }
                } else null,
            )
            NavigationBarItem(
                selected = tab == BottomTab.PRESENT,
                onClick = onPresent,
                icon = {
                    Icon(
                        InterpreterMode,
                        iconDescription(bottomBarFullLabels[4]),
                    )
                },
                label = if (layout.showLabels) {
                    { Text(layout.labels[4], maxLines = 1) }
                } else null,
            )
        }
    }

}
