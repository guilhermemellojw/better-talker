package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M1 — transições do item único do turno. */
class TurnUiStateTest {

    private fun turn() = TurnUiState(turnId = "t1", startedAt = 1_000L)

    @Test
    fun waitingViraReasoningEViraStreaming() {
        val reasoning = turn().withProgress(
            partial = "",
            reasoning = "Pensando no contexto",
            retryNotice = null,
            now = 2_000L,
        )
        assertEquals(TurnPhase.REASONING, reasoning.phase)
        assertEquals(2_000L, reasoning.reasoningStartedAt)
        assertNull(reasoning.reasoningEndedAt)
        assertEquals("Pensando no contexto", reasoning.reasoningText)

        val streaming = reasoning.withProgress(
            partial = "Primeiro token",
            reasoning = "Pensando no contexto",
            retryNotice = null,
            now = 4_500L,
        )
        assertEquals(TurnPhase.STREAMING, streaming.phase)
        assertEquals("Primeiro token", streaming.partialText)
        assertEquals(4_500L, streaming.reasoningEndedAt)
        assertEquals(3, streaming.reasoningSeconds(now = 4_500L))
    }

    @Test
    fun parcialAtualizaSemRecriarTurno() {
        val first = turn().withProgress("a", "", null, now = 1_100L)
        val second = first.withProgress("ab", "", null, now = 1_200L)
        assertEquals(TurnPhase.STREAMING, first.phase)
        assertEquals("ab", second.partialText)
        assertEquals(first.turnId, second.turnId)
    }

    @Test
    fun doneCongelaTextoEFinalMessageId() {
        val streaming = turn().withProgress("parcial", "r", null, now = 2_000L)
        val done = streaming.done("msg-1", "texto final", now = 5_000L)
        assertEquals(TurnPhase.DONE, done.phase)
        assertEquals("msg-1", done.finalMessageId)
        assertEquals("texto final", done.finalText)
        assertEquals("texto final", done.partialText)
        assertTrue(done.isTerminal)
        assertFalse(done.isLive)
    }

    @Test
    fun stopCongelaParcialERaciocinio() {
        val streaming = turn().withProgress("parcial lido", "raciocínio", null, now = 2_000L)
        val stopped = streaming.cancelled()
        assertEquals(TurnPhase.CANCELLED, stopped.phase)
        assertEquals("parcial lido", stopped.partialText)
        assertEquals("raciocínio", stopped.reasoningText)
        assertTrue(stopped.isTerminal)
        // Progresso atrasado do provider não reabre o turno.
        val late = stopped.withProgress("novo", "", null, now = 3_000L)
        assertEquals(TurnPhase.CANCELLED, late.phase)
        assertEquals("parcial lido", late.partialText)
    }

    @Test
    fun errorEMensagemHumanaNaoRegridem() {
        val error = turn().failed("Sem conexão. Tente novamente.")
        assertEquals(TurnPhase.ERROR, error.phase)
        assertEquals("Sem conexão. Tente novamente.", error.error)
        assertEquals(TurnPhase.ERROR, error.done("m", "x", 1L).phase)
    }

    @Test
    fun reasoningSecondsNuncaMostraZero() {
        val reasoning = turn().withProgress("", "r", null, now = 2_000L)
        assertEquals(1, reasoning.reasoningSeconds(now = 2_000L))
        assertEquals(2, reasoning.reasoningSeconds(now = 3_001L))
    }

    @Test
    fun cancelledPayloadPreservaParcialTurnoERaciocinio() {
        val streaming = turn()
            .withProgress("", "raciocínio", null, now = 2_000L)
            .withProgress("parcial congelado", "raciocínio", null, now = 5_000L)
            .cancelled()
        val payload = streaming.cancelledPayload(now = 6_000L)

        assertEquals("parcial congelado", payload["text"])
        assertEquals("1", payload["cancelled"])
        assertEquals("1", payload["partial"])
        assertEquals("t1", payload["turnId"])
        assertEquals("3", payload["reasoningSeconds"])
        assertEquals("COPILOT", payload["origin"])
        // Card-first: turno cancelado não vira card acionável — sem payload
        // de card, sem insert/gate; o parcial fica congelado e read-only.
        assertTrue(payload.keys.none { it.startsWith("card") })
    }
}
