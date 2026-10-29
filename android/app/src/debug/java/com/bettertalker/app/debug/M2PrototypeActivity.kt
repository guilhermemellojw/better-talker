package com.bettertalker.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.data.copilot.TurnUiState
import com.bettertalker.app.ui.theme.BetterTalkerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M2a — protótipo isolado, debug-only. Não usa LLM, rotas, card-first real
 * nem o ChatScreen de produção. O estado é fake e serve para validar motion.
 */
class M2PrototypeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BetterTalkerTheme {
                M2ProtoScreen()
            }
        }
    }
}

private enum class ProtoScope { TOPIC, WHOLE }

private data class ProtoMessage(val id: String, val fromMe: Boolean, val text: String)

private const val FULL_TEXT =
    "O ponto central é que a ordem da criação revela cuidado e propósito. " +
        "Quando olhamos para os céus, vemos estações, ciclos e limites que " +
        "funcionam juntos. Isso nos convida a trazer a mesma ordem para a " +
        "nossa rotina: prioridades claras, pausas conscientes e atenção ao " +
        "que realmente importa."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun M2ProtoScreen() {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var reduced by remember { mutableStateOf(false) }
    val motion = if (reduced) 0 else 180
    var history by remember {
        mutableStateOf(
            listOf(
                ProtoMessage("h1", true, "Como começo a introdução deste ponto?"),
                ProtoMessage(
                    "h2", false,
                    "Comece com uma pergunta que prenda a atenção e conecte o " +
                        "tema ao dia a dia de quem ouve.",
                ),
                ProtoMessage("h3", true, "E qual referência posso usar?"),
                ProtoMessage(
                    "h4", false,
                    "Salmo 103:20 fala dos anjos que cumprem a palavra de Deus. " +
                        "A ideia de ordem e prontidão aparece naturalmente.",
                ),
                ProtoMessage("h5", true, "Resuma em uma frase."),
                ProtoMessage(
                    "h6", false,
                    "A criação revela um Deus de ordem; nossa resposta é refletir " +
                        "essa ordem com calma e propósito.",
                ),
            )
        )
    }
    var turn by remember { mutableStateOf<TurnUiState?>(null) }
    var playing by remember { mutableStateOf(false) }
    var runToken by remember { mutableIntStateOf(0) }
    var retrying by remember { mutableStateOf(false) }
    var retryToken by remember { mutableIntStateOf(0) }
    var standaloneError by remember { mutableStateOf(false) }
    var showTopCard by remember { mutableStateOf(false) }
    var showNestedCard by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }
    var showContinue by remember { mutableStateOf(false) }
    var scopeState by remember { mutableStateOf(ProtoScope.TOPIC) }
    var scopeLoading by remember { mutableStateOf(false) }
    var pendingScope by remember { mutableStateOf<ProtoScope?>(null) }
    var unseen by remember { mutableIntStateOf(0) }
    var seenHistory by remember { mutableIntStateOf(history.size) }
    var expandedReasoning by remember { mutableStateOf(false) }
    var errorDismissed by remember { mutableStateOf(false) }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 8
        }
    }
    val topCardIndex = 1 + history.size
    val turnIndex = topCardIndex + if (showTopCard) 1 else 0
    val fallbackIndex = turnIndex

    // Play fake: WAITING → REASONING → STREAMING → DONE no MESMO turnId.
    LaunchedEffect(playing, runToken) {
        if (!playing) return@LaunchedEffect
        val id = "turn-$runToken"
        turn = TurnUiState(turnId = id, phase = TurnPhase.WAITING, startedAt = System.currentTimeMillis())
        standaloneError = false
        expandedReasoning = false
        errorDismissed = false
        delay(500)
        turn = turn?.copy(
            phase = TurnPhase.REASONING,
            reasoningText = "Analisando o ponto, as fontes e a transição…",
            reasoningStartedAt = System.currentTimeMillis(),
        )
        delay(1_200)
        val full = FULL_TEXT
        var acc = ""
        turn = turn?.copy(
            phase = TurnPhase.STREAMING,
            reasoningEndedAt = System.currentTimeMillis(),
            partialText = acc,
        )
        full.chunked(10).forEach { chunk ->
            if (!playing) return@LaunchedEffect
            acc += chunk
            turn = turn?.copy(
                phase = TurnPhase.STREAMING,
                partialText = acc,
                reasoningEndedAt = turn?.reasoningEndedAt ?: System.currentTimeMillis(),
            )
            delay(130)
        }
        turn = turn?.done("msg-$id", full, System.currentTimeMillis())
        playing = false
    }

    // Retry preserva o parcial: continua a partir do texto congelado.
    LaunchedEffect(retryToken) {
        if (!retrying) return@LaunchedEffect
        val current = turn ?: return@LaunchedEffect
        val existing = current.partialText.ifBlank { current.finalText }
        val remaining = FULL_TEXT.removePrefix(existing).ifBlank { FULL_TEXT }
        var acc = existing
        errorDismissed = false
        turn = current.copy(phase = TurnPhase.STREAMING, error = null, finalText = "")
        remaining.chunked(10).forEach { chunk ->
            if (!retrying) return@LaunchedEffect
            acc += chunk
            turn = turn?.copy(phase = TurnPhase.STREAMING, partialText = acc)
            delay(130)
        }
        turn = turn?.done("msg-retry-${current.turnId}", FULL_TEXT, System.currentTimeMillis())
        retrying = false
    }

    LaunchedEffect(pendingScope) {
        val next = pendingScope ?: return@LaunchedEffect
        scopeLoading = true
        delay(550)
        scopeState = next
        pendingScope = null
        scopeLoading = false
    }

    LaunchedEffect(history.size) {
        if (history.size > seenHistory) {
            if (atBottom) listState.animateScrollToItem(turnIndex.coerceAtLeast(0))
            else unseen += history.size - seenHistory
        }
        seenHistory = history.size
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }
    LaunchedEffect(turn?.partialText?.length, turn?.phase) {
        if (turn != null && atBottom) listState.scrollToItem(turnIndex, Int.MAX_VALUE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("M2a · protótipo") },
                actions = {
                    FilterChip(
                        selected = reduced,
                        onClick = { reduced = !reduced },
                        label = { Text(if (reduced) "motion 0" else "180 ms") },
                    )
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text(
                "fase=${turn?.phase ?: "—"} · turn=${turn?.turnId ?: "—"} · " +
                    "fallback=${if (turn == null && standaloneError) "sim" else "não"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "scope") {
                        ScopeHeader(
                            loading = scopeLoading,
                            scope = scopeState,
                            motion = motion,
                            onToggle = {
                                pendingScope = if (scopeState == ProtoScope.TOPIC) {
                                    ProtoScope.WHOLE
                                } else {
                                    ProtoScope.TOPIC
                                }
                            },
                        )
                    }
                    items(history, key = { it.id }) { message ->
                        ProtoBubble(message)
                    }
                    if (showTopCard) {
                        item(key = "top-card") {
                            Box(
                                Modifier.animateItem(
                                    fadeInSpec = tween(motion),
                                    fadeOutSpec = tween(motion),
                                )
                            ) {
                                ProtoTopCard(onDismiss = { showTopCard = false })
                            }
                        }
                    }
                    if (turn != null) {
                        item(key = "turn-${turn!!.turnId}") {
                            ProtoTurn(
                                turn = turn!!,
                                nestedCard = showNestedCard,
                                notice = showNotice,
                                continueGenerating = showContinue,
                                expanded = expandedReasoning,
                                motion = motion,
                                onToggleReasoning = { expandedReasoning = !expandedReasoning },
                                onRetry = {
                                    if (turn?.phase == TurnPhase.CANCELLED ||
                                        turn?.phase == TurnPhase.ERROR
                                    ) {
                                        retrying = true
                                        retryToken++
                                    }
                                },
                                showError = !errorDismissed,
                                onDismissError = { errorDismissed = true },
                            )
                        }
                    }
                    if (turn == null && standaloneError) {
                        item(key = "fallback-error") {
                            ProtoStandaloneError(
                                onRetry = {
                                    standaloneError = false
                                    playing = true
                                    runToken++
                                },
                                onDismiss = { standaloneError = false },
                            )
                        }
                    }
                }

                // Pílula "N novas" com fade/slide; só quando o usuário saiu do fim.
                androidx.compose.animation.AnimatedVisibility(
                    visible = unseen > 0,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    enter = fadeIn(tween(motion)) + slideInVertically(tween(motion)) { it / 2 },
                    exit = fadeOut(tween(motion)) + slideOutVertically(tween(motion)) { it / 2 },
                ) {
                    AssistChip(
                        onClick = {
                            unseen = 0
                            scope.launch {
                                val target = if (turn != null) turnIndex else fallbackIndex
                                listState.animateScrollToItem(target.coerceAtLeast(0))
                            }
                        },
                        label = { Text("$unseen novas ↓") },
                    )
                }
            }
            ProtoControls(
                reduced = reduced,
                playing = playing,
                canRetry = turn?.phase == TurnPhase.CANCELLED || turn?.phase == TurnPhase.ERROR,
                onPlay = {
                    standaloneError = false
                    playing = true
                    runToken++
                },
                onStop = {
                    playing = false
                    errorDismissed = false
                    val current = turn
                    if (current != null &&
                        (current.phase == TurnPhase.WAITING ||
                            current.phase == TurnPhase.REASONING ||
                            current.phase == TurnPhase.STREAMING)
                    ) {
                        turn = current.copy(phase = TurnPhase.CANCELLED)
                    }
                },
                onRetry = {
                    if (turn?.phase == TurnPhase.CANCELLED || turn?.phase == TurnPhase.ERROR) {
                        errorDismissed = false
                        retrying = true
                        retryToken++
                    }
                },
                onError = {
                    playing = false
                    errorDismissed = false
                    if (turn == null) {
                        standaloneError = true
                    } else {
                        turn = turn?.copy(
                            phase = TurnPhase.ERROR,
                            error = "Não consegui responder agora. Tente novamente.",
                        )
                    }
                },
                onNoTurnError = {
                    playing = false
                    turn = null
                    standaloneError = true
                },
                onToggleTopCard = { showTopCard = !showTopCard },
                onToggleNestedCard = { showNestedCard = !showNestedCard },
                onToggleNotice = { showNotice = !showNotice },
                onToggleContinue = { showContinue = !showContinue },
                onToggleScope = {
                    pendingScope = if (scopeState == ProtoScope.TOPIC) {
                        ProtoScope.WHOLE
                    } else {
                        ProtoScope.TOPIC
                    }
                },
                onNewMessage = {
                    history = history + ProtoMessage(
                        id = "m-${System.currentTimeMillis()}",
                        fromMe = false,
                        text = "Resposta simulada nova para testar a pílula e a âncora.",
                    )
                },
                onToggleReduced = { reduced = !reduced },
            )
        }
    }
}

@Composable
private fun ScopeHeader(
    loading: Boolean,
    scope: ProtoScope,
    motion: Int,
    onToggle: () -> Unit,
) {
    AnimatedContent(
        targetState = if (loading) "loading" else scope.name,
        transitionSpec = {
            if (motion == 0) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(motion)) togetherWith fadeOut(tween(motion))
        },
        label = "scope",
    ) { state ->
        if (state == "loading") {
            ScopeSkeleton()
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Escopo: " + if (scope == ProtoScope.TOPIC) "Tópico 2/6" else "Discurso inteiro",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TextButton(onClick = onToggle) { Text("Trocar") }
                }
            }
        }
    }
}

@Composable
private fun ScopeSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        repeat(2) {
            Box(
                Modifier
                    .fillMaxWidth(if (it == 1) 0.55f else 1f)
                    .height(14.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp))
            )
        }
    }
}

@Composable
private fun ProtoBubble(message: ProtoMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.fromMe) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProtoTurn(
    turn: TurnUiState,
    nestedCard: Boolean,
    notice: Boolean,
    continueGenerating: Boolean,
    expanded: Boolean,
    motion: Int,
    showError: Boolean,
    onToggleReasoning: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (turn.reasoningText.isNotBlank() || turn.reasoningSeconds() != null) {
                ProtoReasoning(
                    turn = turn,
                    expanded = expanded,
                    motion = motion,
                    onToggle = onToggleReasoning,
                )
                Spacer(Modifier.height(8.dp))
            }
            AnimatedContent(
                targetState = turn.phase,
                transitionSpec = {
                    if (motion == 0) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(motion)) togetherWith fadeOut(tween(motion))
                },
                label = "phase",
            ) { phase ->
                when (phase) {
                    TurnPhase.WAITING -> Text(
                        "Preparando o turno…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TurnPhase.REASONING -> Text(
                        "Analisando as fontes e o escopo…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TurnPhase.STREAMING -> StreamingText(turn.partialText, motion)
                    TurnPhase.DONE -> Text(turn.finalText, style = MaterialTheme.typography.bodyLarge)
                    TurnPhase.CANCELLED -> Column {
                        if (turn.partialText.isNotBlank()) {
                            Text(turn.partialText, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (showError) {
                            ProtoInlineError(
                                "Geração parada.",
                                onRetry = onRetry,
                                onDismiss = onDismissError,
                            )
                        }
                    }
                    TurnPhase.ERROR -> Column {
                        if (turn.partialText.isNotBlank()) {
                            Text(turn.partialText, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (showError) {
                            ProtoInlineError(
                                turn.error ?: "Não consegui responder agora.",
                                onRetry = onRetry,
                                onDismiss = onDismissError,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = nestedCard,
                enter = fadeIn(tween(motion)) + expandVertically(tween(motion)),
                exit = fadeOut(tween(motion)) + shrinkVertically(tween(motion)),
            ) {
                ProtoNestedCard()
            }
            AnimatedVisibility(
                visible = notice,
                enter = fadeIn(tween(motion)) + expandVertically(tween(motion)),
                exit = fadeOut(tween(motion)) + shrinkVertically(tween(motion)),
            ) {
                Text(
                    "Aviso do verifier: confira a referência antes de inserir.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            AnimatedVisibility(
                visible = continueGenerating,
                enter = fadeIn(tween(motion)) + expandVertically(tween(motion)),
                exit = fadeOut(tween(motion)) + shrinkVertically(tween(motion)),
            ) {
                Text(
                    "Continuar gerando…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ProtoReasoning(
    turn: TurnUiState,
    expanded: Boolean,
    motion: Int,
    onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(motion))
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onToggle)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (turn.phase == TurnPhase.REASONING) "Pensando…"
                else "Pensou por ${turn.reasoningSeconds() ?: 0}s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded && turn.reasoningText.isNotBlank()) {
            Text(
                turn.reasoningText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StreamingText(text: String, motion: Int) {
    val cursorAlpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = if (motion == 0) 1f else 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (motion == 0) 1 else 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Text(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = cursorAlpha))) {
                append("▍")
            }
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ProtoInlineError(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRetry) { Text("Tentar novamente") }
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    }
}

@Composable
private fun ProtoStandaloneError(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Não consegui responder",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "O turno nem chegou a ser criado. Use Tentar novamente.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetry) { Text("Tentar novamente") }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        }
    }
}

@Composable
private fun ProtoNestedCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Card-first · texto do card",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Este card é filho do turno e entra com AnimatedVisibility, " +
                    "não com animateItem.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = {}) { Text("Usar este texto") }
        }
    }
}

@Composable
private fun ProtoTopCard(onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "ProposalCard · item do LazyColumn",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Este card entra/sai com animateItem (key estável).",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onDismiss) { Text("Dispensar") }
        }
    }
}

@Composable
private fun ProtoControls(
    reduced: Boolean,
    playing: Boolean,
    canRetry: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onError: () -> Unit,
    onNoTurnError: () -> Unit,
    onToggleTopCard: () -> Unit,
    onToggleNestedCard: () -> Unit,
    onToggleNotice: () -> Unit,
    onToggleContinue: () -> Unit,
    onToggleScope: () -> Unit,
    onNewMessage: () -> Unit,
    onToggleReduced: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { AssistChip(onClick = onPlay, label = { Text("Play") }) }
            item { AssistChip(onClick = onStop, label = { Text("Stop") }) }
            item {
                AssistChip(
                    onClick = onRetry,
                    enabled = canRetry,
                    label = { Text("Retry") },
                )
            }
            item { AssistChip(onClick = onError, label = { Text("Erro") }) }
            item { AssistChip(onClick = onNoTurnError, label = { Text("Sem turno") }) }
            item { AssistChip(onClick = onToggleTopCard, label = { Text("Card") }) }
            item { AssistChip(onClick = onToggleNestedCard, label = { Text("Aninhado") }) }
            item { AssistChip(onClick = onToggleNotice, label = { Text("Aviso") }) }
            item { AssistChip(onClick = onToggleContinue, label = { Text("Continuar") }) }
            item { AssistChip(onClick = onToggleScope, label = { Text("Escopo") }) }
            item { AssistChip(onClick = onNewMessage, label = { Text("Nova") }) }
            item {
                FilterChip(
                    selected = reduced,
                    onClick = onToggleReduced,
                    label = { Text(if (reduced) "Reduzido" else "Motion") },
                )
            }
        }
    }
}
