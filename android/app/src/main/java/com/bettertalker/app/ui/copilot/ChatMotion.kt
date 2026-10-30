package com.bettertalker.app.ui.copilot

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.data.copilot.TurnUiState

/**
 * M2a — motion do chat. Puro/testável onde possível; a escala vem da
 * configuração global de animação do Android (0 = reduced motion).
 */
internal const val TURN_CROSSFADE_MS = 180
internal const val CARD_MOTION_MS = 180
internal const val REASONING_MOTION_MS = 200
internal const val PILL_MOTION_MS = 180

/**
 * M2a — regra única de erro:
 *
 * - com turno ativo, o erro vive DENTRO do turno (inclusive CANCELLED/ERROR);
 * - sem turno ativo, fluxos legados usam o `ChatErrorRow` avulso;
 * - nunca os dois.
 */
internal fun shouldShowStandaloneError(
    activeTurn: TurnUiState?,
    runState: ChatRunState,
): Boolean = activeTurn == null &&
    (runState is ChatRunState.Error || runState is ChatRunState.Cancelled)

/** Duração efetiva em ms; escala 0 (ou negativa) = instantâneo. */
internal fun motionMillis(baseMs: Int, scale: Float): Int {
    if (scale <= 0f) return 0
    return (baseMs * scale).toInt().coerceAtLeast(1)
}

/** Escala global de animação do Android (`ANIMATOR_DURATION_SCALE`). */
internal fun systemAnimationScale(context: Context): Float =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )

@Composable
internal fun rememberMotionScale(): Float {
    val context = LocalContext.current
    return remember(context) { systemAnimationScale(context) }
}

/** Agrupa fases que compartilham o mesmo corpo visual (crossfade só entre grupos). */
internal enum class TurnVisualGroup { STATUS, STREAMING, DONE, ERROR }

internal fun TurnPhase.visualGroup(): TurnVisualGroup = when (this) {
    TurnPhase.WAITING, TurnPhase.REASONING -> TurnVisualGroup.STATUS
    TurnPhase.STREAMING -> TurnVisualGroup.STREAMING
    TurnPhase.DONE -> TurnVisualGroup.DONE
    TurnPhase.CANCELLED, TurnPhase.ERROR -> TurnVisualGroup.ERROR
}
