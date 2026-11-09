package com.bettertalker.app.ui.present

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.domain.speech.TribunaCue
import com.bettertalker.app.domain.speech.TribunaCueKind
import com.bettertalker.app.ui.components.NoteImageFullScreen
import com.bettertalker.app.ui.components.NoteImageStrip
import com.bettertalker.app.ui.components.NoteImageThumb
import com.bettertalker.app.ui.stage.StageAccent
import com.bettertalker.app.ui.stage.StageBg
import com.bettertalker.app.ui.stage.StageDim
import com.bettertalker.app.ui.stage.StageFg
import com.bettertalker.app.ui.stage.formatClock
import com.bettertalker.app.ui.stage.stageClockSubtitle
import com.bettertalker.app.ui.stage.stageTimerColor
import com.bettertalker.app.ui.stage.stageTimerState

private val PresentRed = Color(0xFFEF4444)

/**
 * Palco — uma seção por vez, alto contraste, sem edição/TTS/STT/chat. O timer
 * pausa quando o app perde foco e exige retomada explícita.
 */
@Composable
fun PresentStageScreen(
    vm: PresentViewModel,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = ctx as? Activity
    val configuration = LocalConfiguration.current
    val cues by vm.cues.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val scriptItems by vm.scriptItems.collectAsState()
    val scriptIndex by vm.scriptIndex.collectAsState()
    val prep by vm.prep.collectAsState()
    val fontSp by vm.fontSp.collectAsState()
    val totalElapsed by vm.totalElapsed.collectAsState()
    val sectionElapsed by vm.sectionElapsed.collectAsState()
    val running by vm.running.collectAsState()
    val paused by vm.pausedByLifecycle.collectAsState()
    val speakers by vm.speakers.collectAsState()
    val mapMode by vm.mapMode.collectAsState()
    val focusCueIndex by vm.focusCueIndex.collectAsState()
    val currentCue = cues.getOrNull(currentIndex)
    val currentScript = scriptItems.getOrNull(scriptIndex)
    val nextScript = scriptItems.getOrNull(scriptIndex + 1)
    val role = speakers.getOrNull(prep.roleIndex.coerceIn(0, (speakers.size - 1).coerceAtLeast(0)))
    var exitDialog by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val sectionMinutes = currentCue?.targetMinutes ?: 0
    val sectionState = stageTimerState(sectionElapsed, sectionMinutes)
    val scriptMode = prep.mode == PresentMode.SCRIPT
    // v5.1: Mapa/Foco e foco por seção vêm do PresentViewModel/DataStore.
    var previewImage by remember { mutableStateOf<NoteImageEntity?>(null) }

    // Orientação: segue o padrão do dispositivo; "Travar" fixa a atual.
    DisposableEffect(prep.orientation) {
        val previous = activity?.requestedOrientation
        when (prep.orientation) {
            PresentOrientation.PORTRAIT ->
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PresentOrientation.AUTO ->
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            PresentOrientation.LOCK_CURRENT -> {
                val landscape = configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                activity?.requestedOrientation = if (landscape) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
        onDispose {
            activity?.requestedOrientation =
                previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Tela cheia imersiva + tela sempre acesa só no palco.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    // Chamada/perda de foco/tela bloqueada pausam; rotação config change não.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE &&
                activity?.isChangingConfigurations != true
            ) {
                vm.pauseByLifecycle()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { exitDialog = true }

    Box(Modifier.fillMaxSize().background(StageBg)) {
        // Conteúdo (uma seção/turno por vez), com swipe horizontal.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(start = 24.dp, end = 24.dp, top = 96.dp, bottom = 100.dp)
                .pointerInput(prep.mode, currentIndex, scriptIndex) {
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = {
                            if (dragTotal <= -70f) vm.next()
                            else if (dragTotal >= 70f) vm.previous()
                            dragTotal = 0f
                        },
                        onHorizontalDrag = { _, amount -> dragTotal += amount },
                    )
                },
        ) {
            if (scriptMode) {
                ScriptContent(
                    current = currentScript,
                    next = nextScript,
                    role = role,
                    totalTurns = scriptItems.size,
                    index = scriptIndex,
                    fontSp = fontSp,
                )
            } else {
                CueContent(
                    cue = currentCue,
                    fontSp = fontSp,
                    mapMode = mapMode,
                    focusCueIndex = focusCueIndex,
                    sectionElapsed = sectionElapsed,
                    onFocusCue = { vm.setFocusCue(it) },
                    onPreviewImage = { previewImage = it },
                )
            }
        }

        // Cabeçalho fixo: seção + título + timer.
        Surface(
            color = Color(0xE6101724),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { exitDialog = true }) {
                    Icon(Icons.Default.Close, "Encerrar apresentação", tint = StageFg)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "${currentIndex + 1}/${cues.size.coerceAtLeast(1)}",
                        color = StageAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        currentCue?.title?.ifBlank { "Seção ${currentIndex + 1}" }
                            ?: "Palco",
                        color = StageFg,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                if (prep.timerVisible) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatClock(totalElapsed),
                            color = if (running) StageFg else StageDim,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            stageClockSubtitle(sectionElapsed, sectionMinutes, running),
                            color = stageTimerColor(sectionState),
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Navegação manual fixa.
        Surface(
            color = Color(0xE6101724),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canPrevious = if (scriptMode) scriptIndex > 0 else currentIndex > 0
                val canNext = if (scriptMode) scriptIndex < scriptItems.lastIndex
                else currentIndex < cues.lastIndex
                TextButton(
                    onClick = vm::previous,
                    enabled = canPrevious,
                ) { Text("‹ Anterior", color = if (canPrevious) StageFg else StageDim) }
                Spacer(Modifier.weight(1f))
                if (!scriptMode) {
                    val hasCueSheet = currentCue?.capsule?.cues?.isNotEmpty() == true
                    if (hasCueSheet) {
                        TextButton(onClick = { vm.setMapMode(!mapMode) }) {
                            Text(if (mapMode) "Foco" else "Mapa", color = StageAccent)
                        }
                    }
                }
                Text(
                    if (scriptMode) "Turno ${scriptIndex + 1}/${scriptItems.size}"
                    else "Seção ${currentIndex + 1}/${cues.size}",
                    color = StageDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = vm::next,
                    enabled = canNext,
                ) { Text("Próxima ›", color = if (canNext) StageFg else StageDim) }
            }
        }

        // Pausa por ciclo de vida: overlay explícito, sem retomada automática.
        if (paused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(StageBg.copy(alpha = 0.97f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        "Tempo pausado",
                        color = StageFg,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A apresentação foi interrompida. Retome quando estiver pronto.",
                        color = StageDim,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = vm::resumeAfterLifecycle) {
                        Text("Retomar")
                    }
                    TextButton(onClick = { exitDialog = true }) {
                        Text("Encerrar", color = StageDim)
                    }
                }
            }
        }
    }

    if (exitDialog) {
        AlertDialog(
            onDismissRequest = { exitDialog = false },
            title = { Text("Encerrar apresentação?") },
            text = {
                Text(
                    "A seção e o tempo ficam salvos para você continuar de onde parou."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    exitDialog = false
                    vm.endSession(onExit)
                }) { Text("Encerrar") }
            },
            dismissButton = {
                TextButton(onClick = { exitDialog = false }) { Text("Continuar") }
            },
        )
    }

    previewImage?.let { image ->
        NoteImageFullScreen(
            path = image.path,
            caption = image.caption,
            onDismiss = { previewImage = null },
        )
    }
}

@Composable
private fun CueContent(
    cue: PresentCue?,
    fontSp: Int,
    mapMode: Boolean,
    focusCueIndex: Int,
    sectionElapsed: Long,
    onFocusCue: (Int) -> Unit,
    onPreviewImage: (NoteImageEntity) -> Unit,
) {
    if (cue == null) {
        MissingCue("Nenhuma seção disponível.", title = "Sem seção")
        return
    }
    val capsule = cue.capsule
    if (capsule == null) {
        MissingCue("Tribuna não gerada para esta seção. Avance ou abra a Tribuna no editor.")
        return
    }
    if (capsule.cues.isEmpty() && capsule.cueSheetVersion >= 2) {
        MissingCue(
            "Sem lembretes nesta seção. Abra a Tribuna para adicionar.",
            title = "Mapa vazio",
        )
        return
    }

    // v4.2: folha de cues (mapa) ou uma cue em foco. Cápsula antiga cai no
    // layout de 4 campos abaixo (compatibilidade sem migração).
    if (capsule.cues.isNotEmpty()) {
        // Se o modelo não criou cue IMAGE, as imagens da seção ainda ficam
        // acessíveis (toque abre tela cheia) sem entrar no texto.
        if (cue.images.isNotEmpty() && capsule.cues.none { it.kind == TribunaCueKind.IMAGE }) {
            NoteImageStrip(
                images = cue.images,
                thumbSizeDp = 68,
                captionColor = StageDim,
            )
            Spacer(Modifier.height(18.dp))
        }
        val focused = focusCueIndex.coerceIn(0, capsule.cues.lastIndex)
        Text(
            if (mapMode) "MAPA DE LEMBRETES" else "FOCO · ${focused + 1}/${capsule.cues.size}",
            color = StageAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (mapMode) {
            capsule.cues.forEach { current ->
                PresentCueLine(
                    cue = current,
                    fontSp = fontSp,
                    image = presentCueImage(current, cue.images),
                    onPreviewImage = onPreviewImage,
                )
            }
        } else {
            PresentCueLine(
                cue = capsule.cues[focused],
                fontSp = fontSp,
                image = presentCueImage(capsule.cues[focused], cue.images),
                onPreviewImage = onPreviewImage,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onFocusCue((focused - 1).coerceAtLeast(0)) },
                    enabled = focused > 0,
                ) { Text("‹ Lembrete", color = if (focused > 0) StageFg else StageDim) }
                Spacer(Modifier.weight(1f))
                Text("${focused + 1}/${capsule.cues.size}", color = StageDim, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onFocusCue((focused + 1).coerceAtMost(capsule.cues.lastIndex)) },
                    enabled = focused < capsule.cues.lastIndex,
                ) { Text("Lembrete ›", color = if (focused < capsule.cues.lastIndex) StageFg else StageDim) }
            }
        }
        // v4.2: checkpoint explícito do tempo acumulado nesta seção.
        Spacer(Modifier.height(14.dp))
        Text(
            "CHECKPOINT · ${formatClock(sectionElapsed)} acumulado nesta seção · meta ${cue.targetMinutes} min",
            color = StageAccent.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        if (capsule.anchorPhrase.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "ÂNCORA: ${capsule.anchorPhrase}",
                color = StageDim,
                fontSize = (fontSp * 0.68f).sp,
                lineHeight = (fontSp * 1.15f).sp,
            )
        }
        return
    }

    if (cue.images.isNotEmpty()) {
        NoteImageStrip(
            images = cue.images,
            thumbSizeDp = 68,
            captionColor = StageDim,
        )
        Spacer(Modifier.height(18.dp))
    }
    Text(
        "FRASE-ÂNCORA",
        color = StageAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(8.dp))
    Surface(
        color = StageAccent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            capsule.anchorPhrase,
            color = StageFg,
            fontSize = fontSp.sp,
            lineHeight = (fontSp * 1.5).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(18.dp),
        )
    }
    Spacer(Modifier.height(22.dp))
    CueBlock("IDEIA CENTRAL", capsule.mainIdea, fontSp)
    CueBlock("TEXTO-CHAVE", capsule.bibleRef.orEmpty(), fontSp)
    CueBlock("TRANSIÇÃO", capsule.transition.orEmpty(), fontSp, dim = true)
    Text(
        "CHECKPOINT · ${formatClock(sectionElapsed)} acumulado nesta seção · meta ${cue.targetMinutes} min",
        color = StageAccent.copy(alpha = 0.9f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** v4.2: resolve a imagem de uma cue; imagem só aparece se for cue IMAGE. */

private fun presentCueLabel(kind: TribunaCueKind): String = when (kind) {
    TribunaCueKind.HOOK -> "GANCHO"
    TribunaCueKind.CONTEXT -> "CONTEXTO"
    TribunaCueKind.QUESTION -> "PERGUNTA"
    TribunaCueKind.REASONING -> "RACIOCÍNIO"
    TribunaCueKind.SCRIPTURE -> "TEXTO"
    TribunaCueKind.READING -> "LEITURA"
    TribunaCueKind.ILLUSTRATION -> "ILUSTRAÇÃO"
    TribunaCueKind.QUOTE -> "CITAÇÃO"
    TribunaCueKind.APPLICATION -> "APLICAÇÃO"
    TribunaCueKind.IMAGE -> "IMAGEM"
    TribunaCueKind.DELIVERY -> "ENTREGA"
    TribunaCueKind.TRANSITION -> "TRANSIÇÃO"
    TribunaCueKind.INVITATION -> "CONVITE"
    TribunaCueKind.RECAP -> "RECAPITULAÇÃO"
    TribunaCueKind.APPEAL -> "APELO"
}

@Composable
private fun PresentCueLine(
    cue: TribunaCue,
    fontSp: Int,
    image: NoteImageEntity?,
    onPreviewImage: (NoteImageEntity) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Surface(
            color = StageAccent.copy(alpha = 0.16f),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                presentCueLabel(cue.kind),
                color = StageAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            cue.text,
            color = if (cue.kind == TribunaCueKind.DELIVERY) StageDim else StageFg,
            fontSize = (fontSp * 0.92f).sp,
            lineHeight = (fontSp * 1.35f).sp,
            fontWeight = if (cue.kind == TribunaCueKind.SCRIPTURE) {
                FontWeight.SemiBold
            } else {
                FontWeight.Normal
            },
            fontStyle = if (cue.kind == TribunaCueKind.DELIVERY) {
                androidx.compose.ui.text.font.FontStyle.Italic
            } else {
                androidx.compose.ui.text.font.FontStyle.Normal
            },
        )
        if (cue.refs.isNotEmpty()) {
            Text(
                cue.refs.joinToString(" · "),
                color = StageDim,
                fontSize = (fontSp * 0.68f).sp,
            )
        }
        // v4.2: cue de imagem TOCÁVEL (tela cheia); sem autoexibição.
        if (cue.kind == TribunaCueKind.IMAGE && image != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoteImageThumb(
                    path = image.path,
                    caption = cue.imageCaption ?: image.caption,
                    sizeDp = 72,
                    modifier = Modifier
                        .size(72.dp)
                        .clickable { onPreviewImage(image) },
                )
                Text(
                    cue.imageCaption ?: image.caption.ifBlank { "Imagem da seção" },
                    color = StageDim,
                    fontSize = (fontSp * 0.66f).sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun CueBlock(
    label: String,
    text: String,
    fontSp: Int,
    dim: Boolean = false,
) {
    if (text.isBlank()) return
    Text(
        label,
        color = StageDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text,
        color = if (dim) StageDim else StageFg,
        fontSize = (fontSp * 0.78f).sp,
        lineHeight = (fontSp * 1.3f).sp,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ScriptContent(
    current: PresentScriptItem?,
    next: PresentScriptItem?,
    role: String?,
    totalTurns: Int,
    index: Int,
    fontSp: Int,
) {
    if (current == null) {
        MissingCue(
            "Roteiro ainda não escrito. Edite o diálogo no editor.",
            title = "Sem roteiro nesta seção",
        )
        return
    }
    val isRole = role != null && current.turn.speaker.equals(role, ignoreCase = true)
    Text(
        if (role != null) "SEU PAPEL: $role" else "ROTEIRO",
        color = StageAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        current.turn.speaker + if (isRole) " · você" else "",
        color = if (isRole) StageAccent else StageDim,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Surface(
        color = if (isRole) StageAccent.copy(alpha = 0.16f) else Color(0xFF111827),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            current.turn.text,
            color = StageFg,
            fontSize = fontSp.sp,
            lineHeight = (fontSp * 1.5).sp,
            fontWeight = if (isRole) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(18.dp),
        )
    }
    Spacer(Modifier.height(22.dp))
    if (next != null) {
        Text(
            "A SEGUIR",
            color = StageDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${next.turn.speaker}: ${next.turn.text}",
            color = StageDim,
            fontSize = (fontSp * 0.72f).sp,
            lineHeight = (fontSp * 1.2f).sp,
            maxLines = 5,
        )
    } else {
        Text(
            "Fim do roteiro.",
            color = StageDim,
            fontSize = 12.sp,
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Turno ${index + 1}/$totalTurns",
        color = StageDim.copy(alpha = 0.8f),
        fontSize = 11.sp,
    )
}

@Composable
private fun MissingCue(message: String, title: String = "Sem Tribuna nesta seção") {
    Surface(
        color = Color(0xFF111827),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title,
                color = PresentRed,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(message, color = StageDim, fontSize = 13.sp)
        }
    }
}
