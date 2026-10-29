package com.bettertalker.app.ui.present

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apresentar live — host do pré-voo e do palco. A rota é imersiva no palco;
 * o pré-voo usa a navegação normal.
 */
@Composable
fun PresentSessionScreen(
    vm: PresentViewModel,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenTribuna: () -> Unit,
) {
    val loadReady by vm.loadReady.collectAsState()
    val stageStarted by vm.stageStarted.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    if (!loadReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (stageStarted) {
        PresentStageScreen(vm = vm, onExit = onBack)
    } else {
        PresentPrepScreen(
            vm = vm,
            onBack = onBack,
            onOpenEditor = onOpenEditor,
            onOpenTribuna = onOpenTribuna,
        )
    }
}

private data class BatteryInfo(val percent: Int, val charging: Boolean)

private fun readBattery(ctx: Context): BatteryInfo? {
    val intent = ctx.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    ) ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    if (level < 0 || scale <= 0) return null
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryInfo((level * 100) / scale, charging)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresentPrepScreen(
    vm: PresentViewModel,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenTribuna: () -> Unit,
) {
    val title by vm.title.collectAsState()
    val cues by vm.cues.collectAsState()
    val readiness by vm.readiness.collectAsState()
    val demo by vm.isDemonstration.collectAsState()
    val speakers by vm.speakers.collectAsState()
    val saved by vm.savedSession.collectAsState()
    val savedValue = saved
    val prep by vm.prep.collectAsState()
    val fontSp by vm.fontSp.collectAsState()
    val ctx = LocalContext.current
    var sectionPicker by remember { mutableStateOf(false) }
    val battery = remember { readBattery(ctx) }
    val readySections = cues.count { presentCueReady(it, demo) }
    val canEnter = cues.isNotEmpty() && readiness != PresentReadiness.MISSING
    val scroll = rememberScrollState()
    val selectedSection = prep.startIndex.coerceIn(0, (cues.size - 1).coerceAtLeast(0))
    val startLabel = when {
        prep.continueSaved && savedValue != null -> "Seção ${savedValue.index + 1}"
        selectedSection > 0 -> "Seção ${selectedSection + 1}"
        else -> "Começo"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apresentar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenTribuna) { Text("Tribuna") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                presentReadinessLabel(readiness),
                style = MaterialTheme.typography.labelLarge,
                color = when (readiness) {
                    PresentReadiness.READY -> MaterialTheme.colorScheme.primary
                    PresentReadiness.PARTIAL -> MaterialTheme.colorScheme.tertiary
                    PresentReadiness.MISSING -> MaterialTheme.colorScheme.error
                },
            )
            if (readiness == PresentReadiness.PARTIAL) {
                Text(
                    "$readySections de ${cues.size} seções com Tribuna. " +
                        "O palco avisa onde faltar; você pode avançar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (readiness == PresentReadiness.MISSING) {
                Text(
                    "Esta nota ainda não tem Tribuna. Abra o editor e gere a Tribuna antes de apresentar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            PrepTitle("Modo")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = prep.mode == PresentMode.CUES,
                    onClick = { vm.updatePrep(prep.copy(mode = PresentMode.CUES)) },
                    label = { Text("Cues") },
                )
                if (demo) {
                    FilterChip(
                        selected = prep.mode == PresentMode.SCRIPT,
                        onClick = { vm.updatePrep(prep.copy(mode = PresentMode.SCRIPT)) },
                        label = { Text("Roteiro") },
                    )
                }
            }
            if (!demo) {
                Text(
                    "Roteiro fica disponível só para demonstrações.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            PrepTitle("Começar")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (savedValue != null) {
                    FilterChip(
                        selected = prep.continueSaved,
                        onClick = {
                            vm.updatePrep(
                                prep.copy(
                                    continueSaved = true,
                                    startIndex = savedValue.index,
                                )
                            )
                        },
                        label = { Text("Continuar da seção ${savedValue.index + 1}") },
                    )
                }
                FilterChip(
                    selected = !prep.continueSaved,
                    onClick = {
                        vm.updatePrep(
                            prep.copy(continueSaved = false, startIndex = 0)
                        )
                    },
                    label = { Text("Começar do início") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { sectionPicker = true }) {
                    Text("Escolher seção")
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Começa em: $startLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            PrepTitle("Palco")
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Timer visível",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = prep.timerVisible,
                    onCheckedChange = {
                        vm.updatePrep(prep.copy(timerVisible = it))
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fonte",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = { vm.bumpFont(-2) }) { Text("A−") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { vm.bumpFont(2) }) { Text("A+") }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Frase-âncora do palco",
                    fontSize = fontSp.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            PrepTitle("Orientação")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresentOrientation.entries.forEach { orientation ->
                    FilterChip(
                        selected = prep.orientation == orientation,
                        onClick = { vm.updatePrep(prep.copy(orientation = orientation)) },
                        label = { Text(presentOrientationLabel(orientation)) },
                    )
                }
            }

            if (demo && prep.mode == PresentMode.SCRIPT && speakers.size > 1) {
                Spacer(Modifier.height(18.dp))
                PrepTitle("Meu papel")
                TextButton(
                    onClick = {
                        val next = (prep.roleIndex + 1) % speakers.size
                        vm.updatePrep(prep.copy(roleIndex = next))
                    },
                ) {
                    Text("${speakers[prep.roleIndex.coerceIn(0, speakers.lastIndex)]} · trocar")
                }
            }

            Spacer(Modifier.height(18.dp))
            PrepTitle("Antes de entrar")
            Text(
                "A tela fica acesa e o sistema entra em modo imersivo. " +
                    "Silencie notificações e confira a orientação/fonte.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            battery?.let {
                Text(
                    if (presentBatteryLow(it.percent, it.charging)) {
                        "Bateria em ${it.percent}% e sem carregador — carregue antes."
                    } else {
                        "Bateria: ${it.percent}%" + if (it.charging) " · carregando" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (presentBatteryLow(it.percent, it.charging)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = vm::startSession,
                enabled = canEnter,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("Entrar no palco", fontWeight = FontWeight.Bold)
            }
            if (readiness == PresentReadiness.PARTIAL) {
                TextButton(
                    onClick = onOpenTribuna,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Abrir Tribuna para completar") }
            }
            if (readiness == PresentReadiness.MISSING) {
                TextButton(
                    onClick = onOpenEditor,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Abrir editor para gerar Tribuna") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (sectionPicker) {
        ModalBottomSheet(
            onDismissRequest = { sectionPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                "Começar por",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(cues, key = { it.sectionId }) { cue ->
                    val enabled = prep.mode == PresentMode.CUES || cue.turns.isNotEmpty()
                    Surface(
                        color = if (prep.startIndex == cue.index) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        TextButton(
                            onClick = {
                                vm.updatePrep(
                                    prep.copy(
                                        continueSaved = false,
                                        startIndex = cue.index,
                                    )
                                )
                                sectionPicker = false
                            },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    "${cue.index + 1}. ${cue.title}",
                                    textAlign = TextAlign.Start,
                                )
                                Text(
                                    "${cue.minutes} min" +
                                        if (!presentCueReady(cue, demo)) " · sem Tribuna" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrepTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
}
