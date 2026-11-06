package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transições puras do push-to-talk. */
class PttMachineTest {

    @Test
    fun fluxoSemTts_terminaEmIdle() {
        val m = PttMachine()
        assertEquals(PttState.Idle, m.state)

        assertTrue(m.press())
        assertEquals(PttState.Starting, m.state)

        m.engineReady()
        assertEquals(PttState.Listening, m.state)

        m.release()
        assertEquals(PttState.Transcribing, m.state)

        m.transcriptReady()
        assertEquals(PttState.Thinking, m.state)

        m.responseFinished(spoken = false)
        assertEquals(PttState.Idle, m.state)
    }

    @Test
    fun fluxoComTts_passaPorSpeaking() {
        val m = PttMachine()
        m.press()
        m.engineReady()
        m.release()
        m.transcriptReady()
        m.responseFinished(spoken = true)
        assertEquals(PttState.Speaking, m.state)

        m.ttsFinished()
        assertEquals(PttState.Idle, m.state)
    }

    @Test
    fun pressSoEntraEmStartingNosEstadosPermitidos() {
        val m = PttMachine()
        m.press()
        assertFalse("já está starting", m.press())
        m.engineReady()
        assertEquals(PttState.Listening, m.state)
        assertFalse("já está ouvindo", m.press())
        m.release()
        assertEquals(PttState.Transcribing, m.state)
        assertFalse("aguardando transcrição", m.press())
    }

    @Test
    fun bargeInDeSpeakingOuThinkingVoltaParaStarting() {
        val m = PttMachine()
        m.press()
        m.engineReady()
        m.release()
        m.transcriptReady()
        m.responseFinished(spoken = true)
        assertEquals(PttState.Speaking, m.state)
        assertTrue(m.press())
        assertEquals(PttState.Starting, m.state)

        val m2 = PttMachine()
        m2.press()
        m2.engineReady()
        m2.release()
        m2.transcriptReady()
        assertEquals(PttState.Thinking, m2.state)
        assertTrue(m2.press())
        assertEquals(PttState.Starting, m2.state)
    }

    @Test
    fun cancelSempreVoltaParaIdle() {
        val m = PttMachine()
        m.press()
        m.engineReady()
        m.cancel()
        assertEquals(PttState.Idle, m.state)
        m.release()
        assertEquals("soltar no idle não reabre", PttState.Idle, m.state)
    }

    @Test
    fun erroPermiteTentarDeNovo() {
        val m = PttMachine()
        m.fail("sem microfone")
        assertEquals(PttState.Error("sem microfone"), m.state)
        assertTrue(m.press())
        assertEquals(PttState.Starting, m.state)
        m.reset()
        assertEquals(PttState.Idle, m.state)
    }

    @Test
    fun ttsStartedSoMoveDeIdleOuThinking() {
        val m = PttMachine()
        m.ttsStarted()
        assertEquals(PttState.Speaking, m.state)
        m.ttsFinished()
        assertEquals(PttState.Idle, m.state)

        m.press()
        m.engineReady()
        m.release()
        m.transcriptReady()
        m.ttsStarted()
        assertEquals(PttState.Speaking, m.state)
    }
}
