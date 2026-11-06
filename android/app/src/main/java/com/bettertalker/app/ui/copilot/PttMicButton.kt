package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.voice.PttState
import com.bettertalker.app.data.voice.isPttCancelDrag

/**
 * Botão PTT: segurar abre o microfone, soltar envia e arrastar para cima/
 * esquerda cancela. Não tem `onClick` — o gesto é a própria ação.
 */
@Composable
internal fun PttMicButton(
    state: PttState,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)

    val listening = state == PttState.Listening || state == PttState.Starting
    val speaking = state == PttState.Speaking
    val container = when {
        listening -> MaterialTheme.colorScheme.errorContainer
        speaking -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val content = when {
        listening -> MaterialTheme.colorScheme.onErrorContainer
        speaking -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val desc = when (state) {
        PttState.Listening -> "Ouvindo — solte para enviar ou arraste para cancelar"
        PttState.Speaking -> "Falar — interrompe a leitura"
        PttState.Transcribing -> "Transcrevendo"
        PttState.Thinking -> "Pensando"
        PttState.Starting -> "Preparando o microfone"
        else -> "Segure para falar"
    }

    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container)
            .semantics { contentDescription = desc }
            .pointerInput(threshold) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    press()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    var finished = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (isPttCancelDrag(
                                    dx = change.position.x - down.position.x,
                                    dy = change.position.y - down.position.y,
                                    threshold = threshold,
                                )
                            ) {
                                finished = true
                                cancel()
                                change.consume()
                                break
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                finished = true
                                release()
                                break
                            }
                            change.consume()
                        }
                    } finally {
                        // Gesture cancelada (tela saiu, permissão abriu etc.):
                        // o microfone não pode ficar aberto.
                        if (!finished) cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            PttState.Starting, PttState.Transcribing, PttState.Thinking ->
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = content,
                )
            PttState.Speaking -> Icon(Icons.Default.Stop, desc, tint = content)
            else -> Icon(Icons.Default.Mic, desc, tint = content)
        }
    }
}
