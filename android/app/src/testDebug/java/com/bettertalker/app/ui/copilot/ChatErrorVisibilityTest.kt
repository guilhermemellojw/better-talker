package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.data.copilot.TurnUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatErrorVisibilityTest {

    private fun turn(phase: TurnPhase) = TurnUiState(turnId = "t1", phase = phase)

    @Test
    fun erroComTurnoAtivoFicaSoNoTurno() {
        assertFalse(
            shouldShowStandaloneError(
                activeTurn = turn(TurnPhase.ERROR),
                runState = ChatRunState.Error("falhou"),
            )
        )
        assertFalse(
            shouldShowStandaloneError(
                activeTurn = turn(TurnPhase.CANCELLED),
                runState = ChatRunState.Cancelled(),
            )
        )
        assertFalse(
            shouldShowStandaloneError(
                activeTurn = turn(TurnPhase.STREAMING),
                runState = ChatRunState.Error("falhou"),
            )
        )
    }

    @Test
    fun semTurnoAtivoUsaFallbackLegado() {
        assertTrue(
            shouldShowStandaloneError(
                activeTurn = null,
                runState = ChatRunState.Error("falhou"),
            )
        )
        assertTrue(
            shouldShowStandaloneError(
                activeTurn = null,
                runState = ChatRunState.Cancelled(),
            )
        )
    }

    @Test
    fun estadosNormaisNaoMostramErroAvulso() {
        listOf(
            ChatRunState.Idle,
            ChatRunState.Sending,
            ChatRunState.Generating,
            ChatRunState.Success,
        ).forEach { state ->
            assertFalse(shouldShowStandaloneError(activeTurn = null, runState = state))
        }
    }
}
