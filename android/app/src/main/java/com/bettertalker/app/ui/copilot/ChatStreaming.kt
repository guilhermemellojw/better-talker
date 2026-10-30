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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * Atualiza o texto renderizado no máximo a cada [throttleMs]. Mudanças rápidas
 * cancelam o efeito anterior (coalesce), sem animar altura por token.
 */
@Composable
internal fun rememberThrottledStreamingText(
    text: String,
    throttleMs: Long = STREAM_THROTTLE_MS,
): String {
    // Inicializa com o texto atual: recriação de fase não pisca vazio.
    var rendered by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text == rendered) return@LaunchedEffect
        // Primeiro token entra imediatamente; updates rápidos esperam a
        // janela para não parsear markdown a cada emissão.
        if (rendered.isNotEmpty() && text.length > rendered.length) delay(throttleMs)
        rendered = text
    }
    return rendered
}

/** Parcial em markdown, com card shell e cursor de slot fixo. */
@Composable
internal fun StreamingMarkdown(
    text: String,
    showCursor: Boolean,
    modifier: Modifier = Modifier,
) {
    val throttled = rememberThrottledStreamingText(text)
    val blocks = remember(throttled) { streamingBlocks(throttled) }
    val motion = motionMillis(CURSOR_FADE_MS, rememberMotionScale())
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        blocks.forEach { block ->
            // TextForCard já renderiza o shell (“Texto para o card”) com o
            // parcial dentro; as ações só aparecem no card final.
            RenderBlock(block, mark = null, onUseInCard = null)
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
