package com.bettertalker.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.ui.theme.AppPalettes
import com.bettertalker.app.ui.theme.PaletteId
import com.bettertalker.app.ui.theme.PaletteSpec
import com.bettertalker.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * Tela de Aparência (substitui o AlertDialog): tema (Automático/Claro/Escuro)
 * + paleta com miniaturas reais — tocar aplica em tempo real (live preview,
 * a mudança persiste no SettingsStore e o tema do app recompõe).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val mode by settings.themeMode.collectAsState(initial = ThemeMode.AUTO)
    val paletteId by settings.paletteId.collectAsState(initial = PaletteId.SAMSUNG)
    val scope = rememberCoroutineScope()
    var paletteQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aparência") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Tema", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, m ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = { scope.launch { settings.setThemeMode(m) } },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeMode.entries.size,
                                ),
                            ) { Text(themeModeLabel(m)) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Paleta", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = paletteQuery,
                        onValueChange = { paletteQuery = it },
                        placeholder = { Text("Buscar paleta…") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(
                AppPalettes.all.filter {
                    paletteQuery.isBlank() ||
                        it.label.contains(paletteQuery.trim(), ignoreCase = true)
                },
                key = { it.id.name },
            ) { spec ->
                PaletteCard(
                    spec = spec,
                    dark = dark,
                    selected = spec.id == paletteId,
                    onClick = { scope.launch { settings.setPaletteId(spec.id) } },
                )
            }
        }
    }
}

/** Rótulo do modo de tema. Puro/testável. */
internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.AUTO -> "Automático"
    ThemeMode.LIGHT -> "Claro"
    ThemeMode.DARK -> "Escuro"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaletteCard(
    spec: PaletteSpec,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = spec.scheme(dark)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    scheme.primary, scheme.tertiary, scheme.secondary,
                    scheme.surfaceVariant, scheme.surface,
                ).forEach { c ->
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(1.dp, scheme.outline, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Default.Check, "Selecionada",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
