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

/** Folga em dp para considerar o fim da lista alcançado. */
internal const val AT_BOTTOM_TOLERANCE_DP = 8

/**
 * UX-2.1 — o usuário está no fim REAL da lista?
 *
 * O índice do último item visível não basta: uma resposta longa pode ocupar
 * a tela inteira e ainda assim ter conteúdo abaixo. Só consideramos "no fim"
 * quando o último item também está visível e o seu fim está dentro da
 * viewport (com pequena tolerância).
 *
 * `totalItems == 0` = lista vazia/estável no fim.
 * `lastVisibleIndex == null` com itens = layout transitório; não reata o
 * auto-scroll por engano.
 *
 * Puro/testável. Alternativa considerada: `!listState.canScrollForward`.
 * Em repouso os dois concordam, mas `canScrollForward` não é puro/testável
 * e pode oscilar em layout/overscroll; a geometria com tolerância dá ao
 * `followEnd` uma decisão explícita sobre o fim real.
 */
internal fun isAtListBottom(
    totalItems: Int,
    lastVisibleIndex: Int?,
    lastVisibleEnd: Int,
    viewportEnd: Int,
    tolerancePx: Int = 8,
): Boolean {
    if (totalItems <= 0) return true
    val index = lastVisibleIndex ?: return false
    if (index != totalItems - 1) return false
    return lastVisibleEnd <= viewportEnd + tolerancePx.coerceAtLeast(0)
}

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
