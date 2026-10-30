package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.data.copilot.TurnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMotionTest {

    private fun turn(phase: TurnPhase) = TurnUiState(turnId = "t1", phase = phase)

    @Test
    fun erroAvulso_soSemTurnoAtivo() {
        assertTrue(shouldShowStandaloneError(null, ChatRunState.Error("falhou")))
        assertTrue(shouldShowStandaloneError(null, ChatRunState.Cancelled("parada")))
        assertFalse(shouldShowStandaloneError(null, ChatRunState.Idle))
        assertFalse(shouldShowStandaloneError(null, ChatRunState.Sending))
        assertFalse(shouldShowStandaloneError(null, ChatRunState.Generating))
        assertFalse(shouldShowStandaloneError(null, ChatRunState.Success))
    }

    @Test
    fun erroAvulso_nuncaComTurnoAtivo() {
        for (phase in TurnPhase.entries) {
            assertFalse(
                "fase $phase",
                shouldShowStandaloneError(turn(phase), ChatRunState.Error("falhou")),
            )
            assertFalse(
                "fase $phase",
                shouldShowStandaloneError(turn(phase), ChatRunState.Cancelled("parada")),
            )
        }
    }

    @Test
    fun motionMillis_respeitaReducedMotionEEscala() {
        assertEquals(0, motionMillis(180, 0f))
        assertEquals(0, motionMillis(180, -1f))
        assertEquals(90, motionMillis(180, 0.5f))
        assertEquals(180, motionMillis(180, 1f))
        assertEquals(1, motionMillis(1, 0.1f))
    }

    @Test
    fun turnAnchorIndex_usaTurnoNaoCardFinal() {
        // sem contexto: 3 mensagens (0..2) + turno visível = índice 3.
        assertEquals(3, turnAnchorIndex(3, false, false, activeTurnVisible = true))
        // com contexto: desloca 1; turno = 1 + 3 = 4.
        assertEquals(4, turnAnchorIndex(3, true, false, activeTurnVisible = true))
        // sem turno visível: última mensagem (não card/proposta).
        assertEquals(2, turnAnchorIndex(3, false, false, activeTurnVisible = false))
        assertEquals(3, turnAnchorIndex(3, true, false, activeTurnVisible = false))
        // tópico vazio + contexto: o turno vem depois dos dois slots.
        assertEquals(2, turnAnchorIndex(0, true, true, activeTurnVisible = true))
    }

    @Test
    fun visualGroup_agrupaStatusEErro() {
        assertEquals(TurnVisualGroup.STATUS, TurnPhase.WAITING.visualGroup())
        assertEquals(TurnVisualGroup.STATUS, TurnPhase.REASONING.visualGroup())
        assertEquals(TurnVisualGroup.STREAMING, TurnPhase.STREAMING.visualGroup())
        assertEquals(TurnVisualGroup.DONE, TurnPhase.DONE.visualGroup())
        assertEquals(TurnVisualGroup.ERROR, TurnPhase.CANCELLED.visualGroup())
        assertEquals(TurnVisualGroup.ERROR, TurnPhase.ERROR.visualGroup())
    }
}
