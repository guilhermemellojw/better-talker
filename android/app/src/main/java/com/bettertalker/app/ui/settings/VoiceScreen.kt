package com.bettertalker.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.voice.SpeechRecognizerManager
import com.bettertalker.app.data.voice.SystemVoice
import com.bettertalker.app.data.voice.VoiceRouter
import kotlinx.coroutines.launch

/**
 * Ajuste “Voz”: motor de leitura (Piper offline × vozes do sistema), escolha
 * da voz pt-BR, teste e preferência de STT no aparelho.
 *
 * Puro de navegação: não inicia o Piper nem baixa nada; o provisionamento
 * continua no fluxo de Ensaio/Copilot e o fallback é automático.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    settings: SettingsStore,
    voiceRouter: VoiceRouter,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val premium by settings.voicePremiumEnabled.collectAsState(initial = true)
    val selectedVoice by settings.ttsVoiceName.collectAsState(initial = null)
    val sttOnDevice by settings.sttOnDevice.collectAsState(initial = true)
    var voices by remember { mutableStateOf(voiceRouter.systemVoices()) }
    val onDeviceAvailable = remember {
        SpeechRecognizerManager.isOnDeviceAvailable(context)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Voz") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Leitura em voz alta")
            ChoiceRow(
                title = "Piper — voz offline do app",
                subtitle = "Não usa rede. Voz única em português.",
                selected = premium,
                onClick = {
                    voiceRouter.setPremiumEnabled(true)
                    voiceRouter.ensurePiperOrFallback()
                    scope.launch { settings.setVoicePremiumEnabled(true) }
                },
            )
            ChoiceRow(
                title = "Voz do sistema",
                subtitle = "Usa as vozes pt-BR instaladas no aparelho.",
                selected = !premium,
                onClick = {
                    voiceRouter.setPremiumEnabled(false)
                    scope.launch { settings.setVoicePremiumEnabled(false) }
                },
            )
            Text(
                "Se o Piper não estiver disponível, o app volta automaticamente " +
                    "para a voz do sistema.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Voz do sistema (pt-BR)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { voices = voiceRouter.systemVoices() }) {
                    Text("Atualizar")
                }
            }
            if (voices.isEmpty()) {
                Text(
                    "Nenhuma voz pt-BR encontrada ainda. Abra o Copilot ou o " +
                        "Ensaio para inicializar a voz e toque em Atualizar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                ChoiceRow(
                    title = "Padrão do aparelho",
                    subtitle = null,
                    selected = selectedVoice == null,
                    onClick = {
                        voiceRouter.setSystemVoice(null)
                        scope.launch { settings.setTtsVoiceName(null) }
                    },
                )
                voices.forEach { voice ->
                    VoiceRow(
                        voice = voice,
                        selected = selectedVoice == voice.name,
                        onClick = {
                            voiceRouter.setSystemVoice(voice.name)
                            scope.launch { settings.setTtsVoiceName(voice.name) }
                        },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Entrada por voz")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Preferir reconhecimento no aparelho",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (onDeviceAvailable) {
                            "Disponível neste aparelho — o áudio fica no dispositivo."
                        } else {
                            "Indisponível neste aparelho; o sistema pode usar a rede. " +
                                "O app avisa quando cair no serviço do sistema."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = sttOnDevice,
                    onCheckedChange = { checked ->
                        scope.launch { settings.setSttOnDevice(checked) }
                    },
                )
            }

            TextButton(
                onClick = {
                    voiceRouter.speak("Esta é a voz escolhida no Better Talker.")
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Testar voz")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: SystemVoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(voice.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                voice.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
