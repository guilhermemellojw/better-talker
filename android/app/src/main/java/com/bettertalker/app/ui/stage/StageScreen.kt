package com.bettertalker.app.ui.stage

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Ensaio v1 — leitor de tribuna em tela cheia: fundo escuro de alto
 * contraste, texto agregado com fonte ajustável, cronômetro global (com meta
 * e cores) e barra de controles auto-hide. `keepScreenOn` enquanto a tela vive.
 *
 * Toque na tela mostra/esconde os controles; toque no relógio pausa/retoma.
 */
private val StageBg = Color(0xFF030712)
private val StageFg = Color(0xFFF8FAFC)
private val StageDim = Color(0xFF94A3B8)
private val StageAccent = Color(0xFFF59E0B)

/** Cores dos papéis no teleprompter (pela ordem de entrada em cena). */
private val StageSpeakerColors = listOf(
    StageAccent,        // 1º papel — âmbar (cor primária do palco)
    Color(0xFF5EEAD4),  // 2º papel — teal
    Color(0xFFC4B5FD),  // 3º+ — violeta
)

private fun stageSpeakerColor(index: Int): Color =
    StageSpeakerColors[index.coerceAtLeast(0) % StageSpeakerColors.size]
private val StageGreen = Color(0xFF22C55E)
private val StageAmber = Color(0xFFF59E0B)
private val StageRed = Color(0xFFEF4444)

/** Cor do relógio pelo estado da meta. Puro/testável. */
fun stageTimerColor(state: StageTimerState): Color = when (state) {
    StageTimerState.IDLE -> StageFg
    StageTimerState.GREEN -> StageGreen
    StageTimerState.AMBER -> StageAmber
    StageTimerState.RED -> StageRed
}

@Composable
fun StageScreen(vm: StageViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val title by vm.title.collectAsState()
    val content by vm.content.collectAsState()
    val turns by vm.turns.collectAsState()
    val duoRole by vm.duoRole.collectAsState()
    val totalMinutes by vm.totalMinutes.collectAsState()
    val elapsed by vm.elapsed.collectAsState()
    val running by vm.timerRunning.collectAsState()
    val fontSp by vm.fontSp.collectAsState()
    val speaking by vm.ttsSpeaking.collectAsState()
    val speechReady by vm.ttsReady.collectAsState()
    val scroll = rememberScrollState()
    var controls by remember { mutableStateOf(true) }

    // Tela cheia imersiva + tela sempre acesa enquanto o palco está aberto.
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            // T2: sair do palco para o ensaio em voz alta.
            vm.stopSpeech()
        }
    }

    // Barra de controles auto-hide (3s).
    LaunchedEffect(controls) {
        if (controls) {
            delay(3000)
            controls = false
        }
    }

    val timerState = stageTimerState(elapsed, totalMinutes)

    Box(
        Modifier
            .fillMaxSize()
            .background(StageBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controls = !controls }
    ) {
        // Texto do discurso (rolagem manual; fonte ajustável).
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 22.dp, vertical = 96.dp)
        ) {
            if (title.isNotBlank()) {
                Text(
                    title,
                    color = StageDim,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(18.dp))
            }
            if (turns.isEmpty()) {
                Text(
                    content.ifBlank { "Esta nota ainda não tem texto para o ensaio." },
                    color = StageFg,
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.6).sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                // Modo Teleprompter: turnos com papel destacado; "Modo Dupla"
                // escurece as falas que não são do papel selecionado.
                val speakers = turns.map { it.speaker }.distinct()
                Column(verticalArrangement = Arrangement.spacedBy((fontSp * 0.7f).dp)) {
                    turns.forEach { turn ->
                        val dimmed = duoRole != null && !turn.speaker.equals(duoRole, true)
                        val accent = if (dimmed) StageDim else stageSpeakerColor(speakers.indexOf(turn.speaker))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                                    append("${turn.speaker}: ")
                                }
                                withStyle(SpanStyle(color = if (dimmed) StageDim else StageFg)) {
                                    append(turn.text)
                                }
                            },
                            fontSize = fontSp.sp,
                            lineHeight = (fontSp * 1.6).sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(120.dp))
        }

        // Controles (topo; escondem sozinhos).
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            StageControlBar(
                elapsed = elapsed,
                totalMinutes = totalMinutes,
                running = running,
                timerState = timerState,
                onToggleTimer = vm::toggleTimer,
                fontSp = fontSp,
                onFontDelta = vm::bumpFont,
                speaking = speaking,
                speechReady = speechReady,
                onToggleSpeech = vm::toggleSpeech,
                hasDuo = turns.size >= 2,
                duoRole = duoRole,
                onCycleDuo = vm::cycleDuoRole,
                onExit = onBack,
            )
        }

        // Progresso da rolagem (rodapé).
        val progress = if (scroll.maxValue > 0) {
            (scroll.value.toFloat() / scroll.maxValue).coerceIn(0f, 1f)
        } else 0f
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(StageAccent)
            )
        }
    }
}

@Composable
private fun StageControlBar(
    elapsed: Long,
    totalMinutes: Int,
    running: Boolean,
    timerState: StageTimerState,
    onToggleTimer: () -> Unit,
    fontSp: Int,
    onFontDelta: (Int) -> Unit,
    speaking: Boolean,
    speechReady: Boolean,
    onToggleSpeech: () -> Unit,
    hasDuo: Boolean = false,
    duoRole: String? = null,
    onCycleDuo: () -> Unit = {},
    onExit: () -> Unit,
) {
    Surface(
        color = Color(0xE6101724),
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Relógio: toque pausa/retoma (tempo de preparação não conta).
            Column(
                Modifier
                    .clickable { onToggleTimer() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    formatClock(elapsed),
                    color = stageTimerColor(timerState),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stageClockSubtitle(elapsed, totalMinutes, running),
                    color = StageDim,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onFontDelta(-2) }) {
                Text("A−", color = StageFg, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { onFontDelta(2) }) {
                Text("A+", color = StageFg, fontWeight = FontWeight.Bold)
            }
            // T2: ensaio em voz alta (▶ Ouvir / ⏸ Parar) — mesmo rótulo do chat.
            TextButton(onClick = onToggleSpeech, enabled = speechReady) {
                Text(
                    com.bettertalker.app.ui.copilot.speakButtonLabel(speaking),
                    color = if (speechReady) StageFg else StageDim,
                    fontWeight = FontWeight.Bold,
                )
            }
            // Modo Teleprompter: "Modo Dupla" — destaca um papel (escurece o outro).
            if (hasDuo) {
                TextButton(onClick = onCycleDuo) {
                    Text(
                        if (duoRole == null) "Dupla" else "Dupla: ${duoRole.take(8)}",
                        color = if (duoRole != null) StageAccent else StageDim,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Default.Close, "Sair do Ensaio", tint = StageFg)
            }
        }
    }
}

/** Subtítulo do relógio: meta/restante (ou tempo excedido). Puro/testável. */
fun stageClockSubtitle(elapsedSec: Long, targetMin: Int, running: Boolean): String {
    val pause = if (running) "" else " · pausado"
    if (targetMin <= 0) return "sem meta$pause"
    val over = stageOvertimeSeconds(elapsedSec, targetMin)
    val base = if (over > 0) {
        "meta ${formatClock(targetMin * 60L)} · excedeu ${formatClock(over)}"
    } else {
        "meta ${formatClock(targetMin * 60L)} · restam ${formatClock(stageRemainingSeconds(elapsedSec, targetMin))}"
    }
    return base + pause
}
