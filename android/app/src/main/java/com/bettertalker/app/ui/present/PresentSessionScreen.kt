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
import com.bettertalker.app.ui.bible.BibleReaderViewModel
import com.bettertalker.app.ui.components.NoteImageStrip
import java.io.File

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
    /** v5.2: leitor TNM compartilhado com a Tribuna. */
    bibleVm: BibleReaderViewModel,
    /** v5.2: abrir a Biblioteca quando a TNM não estiver no acervo. */
    onOpenLibrary: () -> Unit = {},
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
        PresentStageScreen(
            vm = vm,
            onExit = onBack,
            bibleVm = bibleVm,
            onOpenLibrary = onOpenLibrary,
        )
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
    // v5.3: imagem é recurso de discurso; na demonstração nada de imagens.
    val imageSections = if (demo) emptyList() else cues.filter { it.sectionImages.isNotEmpty() }
    val noteImages = if (demo) {
        emptyList()
    } else {
        cues.flatMap { it.noteImages }
            .distinctBy { it.id }
            .filter { it.sectionId == null }
    }
    val imageCheck = remember(cues, demo) {
        if (demo) {
            PresentImageCheck()
        } else {
            presentImageCheck(cues) { path -> File(path).exists() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preparar palco") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    TextButton(
                        onClick = if (demo) onOpenEditor else onOpenTribuna,
                    ) {
                        Text(if (demo) "Editor" else "Tribuna")
                    }
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
                presentReadinessLabel(readiness, demo),
                style = MaterialTheme.typography.labelLarge,
                color = when (readiness) {
                    PresentReadiness.READY -> MaterialTheme.colorScheme.primary
                    PresentReadiness.PARTIAL -> MaterialTheme.colorScheme.tertiary
                    PresentReadiness.MISSING -> MaterialTheme.colorScheme.error
                },
            )
            if (readiness == PresentReadiness.PARTIAL) {
                Text(
                    if (demo) {
                        "$readySections de ${cues.size} seções com roteiro. " +
                            "O palco avisa onde faltar; você pode avançar."
                    } else {
                        "$readySections de ${cues.size} seções com Tribuna. " +
                            "O palco avisa onde faltar; você pode avançar."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (readiness == PresentReadiness.MISSING) {
                Text(
                    if (demo) {
                        "Esta demonstração ainda não tem roteiro. " +
                            "Abra o editor e escreva o diálogo antes de apresentar."
                    } else {
                        "Esta nota ainda não tem Tribuna. " +
                            "Abra o editor e gere a Tribuna antes de apresentar."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // v5.3: imagens revisáveis antes de entrar no palco. A cue de
            // imagem pode referenciar qualquer imagem da nota; a seção vem
            // primeiro na UI.
            if (!demo && imageSections.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                PrepTitle("Imagens por seção")
                Text(
                    "Toque em uma miniatura para revisar em tela cheia. " +
                        "A imagem é do orador; nada vai para a TV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                imageSections.forEach { cue ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${cue.index + 1}. ${cue.title}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    NoteImageStrip(images = cue.sectionImages, thumbSizeDp = 68)
                }
            }
            if (noteImages.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                PrepTitle("Imagens da nota")
                Text(
                    "Disponíveis para qualquer lembrete de imagem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                NoteImageStrip(images = noteImages, thumbSizeDp = 68)
            }
            if (imageCheck.imageCues > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Imagens: ${imageCheck.imageCues} lembrete(s) · " +
                        "${imageCheck.withCaption} com legenda · " +
                        "${imageCheck.resolved} pronta(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (imageCheck.allResolved) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!imageCheck.allResolved) {
                    Text(
                        "Sem imagem: ${imageCheck.missing.joinToString("; ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (imageCheck.withCaption < imageCheck.imageCues) {
                    Text(
                        "Alguns lembretes de imagem estão sem legenda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            PrepTitle("Modo")
            if (demo) {
                // v5.3: demonstração só tem roteiro (completo ou turno atual).
                Text(
                    "Roteiro da demonstração",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Mostrar só o turno atual",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "O padrão é o roteiro completo da seção.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = prep.scriptFocusOnly,
                        onCheckedChange = {
                            vm.updatePrep(prep.copy(scriptFocusOnly = it))
                        },
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = { vm.updatePrep(prep.copy(mode = PresentMode.CUES)) },
                        label = { Text("Lembretes") },
                    )
                }
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
            Text(
                "Offline: o Palco não usa internet e não envia imagens para a TV.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
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
                    onClick = if (demo) onOpenEditor else onOpenTribuna,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (demo) "Abrir editor para completar o roteiro"
                        else "Abrir Tribuna para completar",
                    )
                }
            }
            if (readiness == PresentReadiness.MISSING) {
                TextButton(
                    onClick = onOpenEditor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (demo) "Abrir editor para escrever o roteiro"
                        else "Abrir editor para gerar Tribuna",
                    )
                }
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
                    val enabled = prep.mode == PresentMode.CUES || cue.allScript.isNotEmpty()
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
                                        if (!presentCueReady(cue, demo)) {
                                            if (demo) " · sem roteiro" else " · sem Tribuna"
                                        } else {
                                            ""
                                        },
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
