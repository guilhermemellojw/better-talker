package com.bettertalker.app.ui.stage

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bettertalker.app.domain.coach.formatCoachDuration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Ensaio v1 — leitor de tribuna em tela cheia: fundo escuro de alto
 * contraste, texto agregado com fonte ajustável, cronômetro global (com meta
 * e cores) e barra de controles fixa. `keepScreenOn` enquanto a tela vive.
 *
 * Toque no relógio pausa/retoma. Os controles não somem sozinhos.
 */
private val StageBg = Color(0xFF030712)
private val StageFg = Color(0xFFF8FAFC)
private val StageDim = Color(0xFF94A3B8)
private val StageAccent = Color(0xFFF59E0B)

/** Cores dos papéis — fundo escuro (Ensaio) e fundo claro (Tribuna). */
private val StageSpeakerColorsDark = listOf(
    StageAccent,        // 1º papel — âmbar (cor primária do palco)
    Color(0xFF5EEAD4),  // 2º papel — teal
    Color(0xFFC4B5FD),  // 3º+ — violeta
)
private val StageSpeakerColorsLight = listOf(
    Color(0xFFB45309),  // âmbar escuro (contraste sobre fundo claro)
    Color(0xFF0F766E),  // teal escuro
    Color(0xFF6D28D9),  // violeta escuro
)

/**
 * Cores dos papéis reusadas pela Tribuna (roteiro de demos).
 * [dark] = fundo escuro (Ensaio sempre; Tribuna conforme o tema).
 */
internal fun stageSpeakerColor(index: Int, dark: Boolean = true): Color {
    val list = if (dark) StageSpeakerColorsDark else StageSpeakerColorsLight
    return list[index.coerceAtLeast(0) % list.size]
}
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
    val analysisState by vm.analysisState.collectAsState()
    val analysisElapsed by vm.analysisElapsed.collectAsState()
    val scroll = rememberScrollState()
    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micDenied = false
            vm.startAnalysis()
        } else {
            micDenied = true
        }
    }
    val requestAnalysis: () -> Unit = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.startAnalysis()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
            // T3-A: microfone nunca fica aberto fora da tela.
            vm.dismissAnalysis()
        }
    }

    // v2.7.2 (Sessão 2): controles fixos — sem auto-hide de 3s e sem
    // toque para esconder. O Ensaio precisa do timer/controles à mão.
    val timerState = stageTimerState(elapsed, totalMinutes)

    Box(
        Modifier
            .fillMaxSize()
            .background(StageBg)
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

        // Controles fixos no topo.
        Box(Modifier.align(Alignment.TopCenter)) {
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
                analysisActive = isAnalysisActive(analysisState),
                onStartAnalysis = requestAnalysis,
                onExit = onBack,
            )
        }

        // T3-A: barra de controle da análise (iniciar → pausar → retomar → parar).
        StageAnalysisBanner(
            state = analysisState,
            elapsedSec = analysisElapsed,
            onPause = vm::pauseAnalysis,
            onResume = vm::resumeAnalysis,
            onStop = vm::stopAnalysis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 84.dp, start = 12.dp, end = 12.dp),
        )
        if (micDenied) {
            MicDeniedBanner(
                onRetry = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onDismiss = { micDenied = false },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 36.dp),
            )
        }

        // Modo Teleprompter: chip do "Modo Dupla" (papel em destaque; escurece
        // as falas do outro) — no rodapé para não lotar a barra de cima.
        if (turns.size >= 2) {
            Surface(
                color = Color(0xE6101724),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp),
            ) {
                TextButton(onClick = vm::cycleDuoRole) {
                    Text(
                        if (duoRole == null) "Modo Dupla" else "Dupla: $duoRole",
                        color = if (duoRole != null) StageAccent else StageDim,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
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

    val result = analysisState as? AnalysisState.Result
    if (result != null) {
        AnalysisResultSheet(
            result = result,
            speaking = speaking,
            speechReady = speechReady,
            onSpeak = vm::toggleFeedbackSpeech,
            onRestart = vm::restartAnalysis,
            onDismiss = vm::dismissAnalysis,
        )
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
    analysisActive: Boolean,
    onStartAnalysis: () -> Unit,
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
            // T3-A: porta do Ensaio com análise (STT local, sem áudio salvo).
            IconButton(
                onClick = onStartAnalysis,
                enabled = !analysisActive,
                modifier = Modifier.semantics {
                    contentDescription = "Ensaio com análise"
                },
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (analysisActive) StageDim else StageFg,
                )
            }
            // T2: ensaio em voz alta (▶ Ouvir / ⏸ Parar) — mesmo rótulo do chat.
            TextButton(onClick = onToggleSpeech, enabled = speechReady) {
                Text(
                    com.bettertalker.app.ui.copilot.speakButtonLabel(speaking),
                    color = if (speechReady) StageFg else StageDim,
                    fontWeight = FontWeight.Bold,
                )
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

/** Barra de controle: iniciar → pausar → retomar → parar. */
@Composable
private fun StageAnalysisBanner(
    state: AnalysisState,
    elapsedSec: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AnalysisState.Idle, is AnalysisState.Result -> Unit
        AnalysisState.Listening -> AnalysisSurface(modifier) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "● Ouvindo… ${formatClock(elapsedSec)}",
                        color = StageRed,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Áudio não é gravado.", color = StageDim, fontSize = 11.sp)
                }
                TextButton(onClick = onPause) { Text("Pausar", color = StageFg) }
                TextButton(onClick = onStop) {
                    Text("Parar", color = StageFg, fontWeight = FontWeight.Bold)
                }
            }
        }
        AnalysisState.Paused -> AnalysisSurface(modifier) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "⏸ Pausado — ${formatClock(elapsedSec)}",
                        color = StageAmber,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Transcrição preservada.", color = StageDim, fontSize = 11.sp)
                }
                TextButton(onClick = onResume) { Text("Retomar", color = StageFg) }
                TextButton(onClick = onStop) {
                    Text("Parar", color = StageFg, fontWeight = FontWeight.Bold)
                }
            }
        }
        AnalysisState.Processing -> AnalysisSurface(modifier) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).height(18.dp),
                    strokeWidth = 2.dp,
                    color = StageAccent,
                )
                Spacer(Modifier.width(10.dp))
                Text("Analisando…", color = StageFg, fontWeight = FontWeight.Bold)
            }
        }
        is AnalysisState.Interrupted -> AnalysisSurface(modifier) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(
                    "⚠ Serviço de voz interrompeu",
                    color = StageAmber,
                    fontWeight = FontWeight.Bold,
                )
                Text(state.message, color = StageFg, fontSize = 12.sp)
                Text("Transcrição preservada.", color = StageDim, fontSize = 11.sp)
                Row {
                    TextButton(onClick = onResume) { Text("Retomar") }
                    TextButton(onClick = onStop) { Text("Parar") }
                }
            }
        }
    }
}

@Composable
private fun AnalysisSurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = Color(0xE6101724),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) { content() }
}

/** CTA quando a permissão do microfone é negada; o timer não para. */
@Composable
private fun MicDeniedBanner(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xE6101724),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                "Microfone não permitido. O timer continua.",
                color = StageFg,
                fontWeight = FontWeight.Medium,
            )
            Row {
                TextButton(onClick = onRetry) { Text("Permitir") }
                TextButton(onClick = onDismiss) { Text("Agora não") }
            }
        }
    }
}

/** Sheet com métricas + 4 blocos de feedback local. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisResultSheet(
    result: AnalysisState.Result,
    speaking: Boolean,
    speechReady: Boolean,
    onSpeak: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val metrics = result.metrics
    val feedback = result.feedback
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Resultado do ensaio",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Fala: ${formatCoachDuration(metrics.speechDurationSec)} · " +
                    "Sessão: ${formatCoachDuration(metrics.sessionDurationSec)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "${metrics.spokenWords} palavras · ${metrics.wpm} wpm " +
                    "(alvo ${metrics.targetWpm})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Cobertura ${metrics.coveragePercent}% · " +
                    "Muletas ${metrics.fillerTotal}" +
                    if (metrics.fillerCounts.isNotEmpty()) {
                        " (" + metrics.fillerCounts.entries.joinToString(", ") {
                            "${it.key} ${it.value}"
                        } + ")"
                    } else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            result.warning?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Aviso: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            FeedbackBlock("O que foi bem", feedback.good)
            FeedbackBlock(
                "O que ajustar",
                feedback.adjust.ifEmpty { listOf("Nada a ajustar — mantenha o padrão.") },
            )
            FeedbackBlock("Padrão observado", listOf(feedback.pattern))
            FeedbackBlock("Exercício", listOf(feedback.exercise))
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onRestart,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Treinar de novo") }
                TextButton(
                    onClick = onSpeak,
                    enabled = speechReady,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        if (speaking) "⏸ Parar" else "▶ Ouvir feedback",
                        color = if (speechReady) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Descartar") }
            }
            Text(
                "Áudio não foi gravado. A transcrição foi descartada.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeedbackBlock(title: String, lines: List<String>) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
    lines.forEach { line ->
        Text(
            "• $line",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
