package com.bettertalker.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.ui.theme.PaletteId
import com.bettertalker.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * #10b — Settings centralizada: os 3 destinos que antes viviam num dropdown
 * "de conta" (Conta / Aparência / IA). O dialog de Aparência vive aqui.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenModel: () -> Unit,
) {
    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.AUTO)
    val paletteId by settings.paletteId.collectAsState(initial = PaletteId.SAMSUNG)
    val scope = rememberCoroutineScope()
    var showAppearance by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            SettingsTile(
                icon = Icons.Default.AccountCircle,
                title = "Conta",
                subtitle = "Login Google, sincronização",
                onClick = onOpenAccount,
            )
            SettingsTile(
                icon = Icons.Default.Contrast,
                title = "Aparência",
                subtitle = "Tema e paleta",
                onClick = { showAppearance = true },
            )
            SettingsTile(
                icon = Icons.Default.SmartToy,
                title = "IA (DeepSeek)",
                subtitle = "Chave de API e modelo",
                onClick = onOpenModel,
            )
        }
    }

    if (showAppearance) {
        AppearanceDialog(
            themeMode = themeMode,
            paletteId = paletteId,
            onThemeChange = { scope.launch { settings.setThemeMode(it) } },
            onPaletteChange = { scope.launch { settings.setPaletteId(it) } },
            onDismiss = { showAppearance = false },
        )
    }
}

/** #10b: tile de navegação — ícone + título/subtítulo + chevron. */
@Composable
private fun SettingsTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Icon(
                icon, null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
