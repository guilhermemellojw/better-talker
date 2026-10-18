package com.bettertalker.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    voiceStore: com.bettertalker.app.data.voice.PiperModelStore,
    voiceRouter: com.bettertalker.app.data.voice.VoiceRouter,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenModel: () -> Unit,
    /** Tela cheia de Aparência (substitui o dialog). */
    onOpenAppearance: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // #23-fix-a: voz premium (Piper).
    val voiceState by voiceStore.state.collectAsState()
    val premiumEnabled by settings.voicePremiumEnabled.collectAsState(initial = false)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

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
                onClick = onOpenAppearance,
            )
            SettingsTile(
                icon = Icons.Default.SmartToy,
                title = "IA (DeepSeek)",
                subtitle = "Chave de API e modelo",
                onClick = onOpenModel,
            )
            // #23-fix-a: voz premium (Piper Jeff) — preparar / preparando / pronto.
            // #23-fix-b: sem download — o modelo é embutido e provisionado local.
            VoicePremiumTile(
                state = voiceState,
                enabled = premiumEnabled,
                onDownload = { downloadJob = scope.launch { voiceStore.ensureProvisioned() } },
                onCancel = { downloadJob?.cancel(); downloadJob = null },
                onToggle = { desired ->
                    when (
                        com.bettertalker.app.data.voice.resolveVoicePremiumToggle(
                            desired, voiceState.downloaded,
                        )
                    ) {
                        com.bettertalker.app.data.voice.VoicePremiumToggle.Activated -> {
                            scope.launch { settings.setVoicePremiumEnabled(true) }
                            voiceRouter.setPremiumEnabled(true)
                        }
                        com.bettertalker.app.data.voice.VoicePremiumToggle.Deactivated -> {
                            scope.launch { settings.setVoicePremiumEnabled(false) }
                            voiceRouter.setPremiumEnabled(false)
                        }
                        com.bettertalker.app.data.voice.VoicePremiumToggle.NeedsDownload ->
                            android.widget.Toast.makeText(
                                ctx, "Prepare a voz primeiro.", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                    }
                },
                onDelete = {
                    voiceStore.delete()
                    scope.launch { settings.setVoicePremiumEnabled(false) }
                    voiceRouter.setPremiumEnabled(false)
                },
            )
        }
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

/** #23-fix-a: tile da voz premium — 3 estados (baixar / baixando / pronto). */
@Composable
private fun VoicePremiumTile(
    state: com.bettertalker.app.data.voice.PiperModelStore.State,
    enabled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.RecordVoiceOver, null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Voz premium (Piper)", style = MaterialTheme.typography.titleMedium)
                    val subtitle = when {
                        state.downloading ->
                            "Preparando voz... ${(state.progress * 100).toInt()}%"
                        state.downloaded ->
                            "Voz pronta (Jeff). Funciona 100% offline."
                        else ->
                            "Voz mais natural, incluída no app (63MB)."
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                when {
                    state.downloading -> TextButton(onClick = onCancel) { Text("Cancelar") }
                    state.downloaded -> Switch(checked = enabled, onCheckedChange = onToggle)
                    else -> TextButton(onClick = onDownload) { Text("Preparar voz") }
                }
            }
            if (state.downloaded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDelete) {
                        Text("Remover modelo", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
