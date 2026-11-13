package com.bettertalker.app.ui.copilot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.ui.components.MdBlock
import com.bettertalker.app.ui.components.parseBlocks
import kotlinx.coroutines.delay

/**
 * M2b — streaming com markdown.
 *
 * O parcial é parseado em blocos a cada ~100 ms (não por token), o card
 * `<texto-do-card>` já nasce com o shell e o cursor tem fade. Nada aqui anima
 * altura do texto; o slot do cursor é fixo para não reflowar.
 */
internal const val STREAM_THROTTLE_MS = 100L
internal const val CURSOR_FADE_MS = 150

/** Blocos do parcial (puro/testável): markdown + card shell + tags limpas. */
internal fun streamingBlocks(text: String): List<MdBlock> =
    if (text.isBlank()) emptyList() else cleanBlocks(parseBlocks(text))

/**
 * M2b — o parcial congelado no CANCELLED usa o mesmo parser do streaming;
 * devolve vazio quando só restaram marcadores internos.
 */
internal fun cancelledPartialBlocks(text: String): List<MdBlock> =
    streamingBlocks(text).filterNot { block ->
        when (block) {
            is MdBlock.Title -> block.text.isBlank()
            is MdBlock.Quote -> block.text.isBlank()
            is MdBlock.Bullet -> block.text.isBlank()
            is MdBlock.Check -> block.text.isBlank()
            is MdBlock.Para -> block.text.isBlank()
            is MdBlock.TextForCard -> block.text.isBlank()
            MdBlock.Divider -> false
        }
    }

/**
 * Throttle por fase: terminal (DONE/CANCELLED/ERROR) renderiza o parcial
 * imediatamente — o stop não pode congelar um texto 100 ms atrasado.
 */
internal fun streamThrottleFor(phase: TurnPhase): Long = when (phase) {
    TurnPhase.DONE, TurnPhase.CANCELLED, TurnPhase.ERROR -> 0L
    else -> STREAM_THROTTLE_MS
}

/** TalkBack: anúncio curto por fase; ERROR fica com o ChatErrorRow. */
internal fun turnStatusAnnouncement(phase: TurnPhase): String? = when (phase) {
    TurnPhase.WAITING, TurnPhase.REASONING -> "Pensando"
    TurnPhase.STREAMING -> "Gerando"
    TurnPhase.CANCELLED -> "Interrompido"
    TurnPhase.ERROR -> null
    TurnPhase.DONE -> "Resultado pronto"
}

/** TalkBack: pílula “N novas”. */
internal fun unseenAnnouncement(count: Int): String =
    if (count == 1) "1 nova mensagem" else "$count novas mensagens"

/**
 * Atualiza o texto renderizado no máximo a cada [throttleMs]. O relógio é
 * periódico e NÃO reinicia a cada token: o loop lê o último texto via
 * [rememberUpdatedState] e publica a próxima revisão na janela seguinte.
 * Terminal ([throttleMs] == 0) publica o texto final imediatamente.
 */
@Composable
internal fun rememberThrottledStreamingText(
    text: String,
    throttleMs: Long = STREAM_THROTTLE_MS,
): String {
    // Inicializa com o texto atual: recriação de fase não pisca vazio.
    var rendered by remember { mutableStateOf(text) }
    val latest by rememberUpdatedState(text)
    // Terminal: qualquer texto que chegue depois do DONE/CANCELLED entra
    // imediatamente, sem esperar a janela.
    LaunchedEffect(text, throttleMs) {
        if (throttleMs <= 0 && text != rendered) rendered = text
    }
    LaunchedEffect(throttleMs) {
        if (throttleMs <= 0) {
            rendered = latest
            return@LaunchedEffect
        }
        // Throttle real: a cada janela, mostra o último texto disponível.
        // Se o stream parar, o valor final já está em `latest`; no DONE o
        // efeito acima roda com 0 e publica sem esperar.
        while (true) {
            delay(throttleMs)
            val next = latest
            if (next != rendered) rendered = next
        }
    }
    return rendered
}

/** Parcial em markdown, com card shell e cursor de slot fixo. */
@Composable
internal fun StreamingMarkdown(
    text: String,
    showCursor: Boolean,
    throttleMs: Long = STREAM_THROTTLE_MS,
    leadingMark: AnnotatedString? = null,
    modifier: Modifier = Modifier,
) {
    val throttled = rememberThrottledStreamingText(text, throttleMs)
    val blocks = remember(throttled) { streamingBlocks(throttled) }
    val motion = motionMillis(CURSOR_FADE_MS, rememberMotionScale())
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        var markPending = leadingMark
        blocks.forEach { block ->
            // Mesmo selo do ChatMessageText final: o primeiro bloco de texto
            // recebe o "BT ·", então a primeira linha não muda no handoff.
            val isTextBlock = block is MdBlock.Title || block is MdBlock.Quote ||
                block is MdBlock.Bullet || block is MdBlock.Check || block is MdBlock.Para
            val mark = if (isTextBlock) markPending else null
            if (isTextBlock) markPending = null
            // TextForCard já renderiza o shell (“Texto para o card”) com o
            // parcial dentro; as ações só aparecem no card final.
            RenderBlock(block, mark = mark, onUseInCard = null)
        }
        // Slot fixo: o texto não reflowa quando o cursor some no DONE/erro.
        Column(Modifier.height(18.dp)) {
            AnimatedVisibility(
                visible = showCursor,
                enter = fadeIn(tween(motion)),
                exit = fadeOut(tween(motion)),
            ) {
                StreamingCursor()
            }
        }
    }
}

@Composable
private fun StreamingCursor() {
    val scale = rememberMotionScale()
    val alpha = if (scale <= 0f) 1f else {
        val a by rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cursorAlpha",
        )
        a
    }
    Text(
        "▍",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
    )
}
