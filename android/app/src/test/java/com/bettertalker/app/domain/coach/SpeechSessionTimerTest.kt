package com.bettertalker.app.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tempo de fala vs. tempo total: pausas não entram no wpm. */
class SpeechSessionTimerTest {

    @Test
    fun pausaNaoEntraNoTempoDeFalaMasEntraNoTotal() {
        var now = 0L
        val timer = SpeechSessionTimer { now }
        timer.start()
        timer.beginSpeech()
        now = 30_000
        assertEquals(30, timer.speechSeconds())
        assertEquals(30, timer.sessionSeconds())

        timer.pause()
        assertFalse(timer.isRunning)
        now = 45_000
        assertEquals(30, timer.speechSeconds())
        assertEquals(45, timer.sessionSeconds())

        timer.resume()
        assertTrue(timer.isRunning)
        now = 60_000
        assertEquals(45, timer.speechSeconds())
        assertEquals(60, timer.sessionSeconds())
    }

    @Test
    fun tempoDeFalaSoComecaNaPrimeiraFala() {
        var now = 0L
        val timer = SpeechSessionTimer { now }
        timer.start()
        now = 5_000
        assertEquals(0, timer.speechSeconds())
        assertEquals(5, timer.sessionSeconds())
        timer.beginSpeech()
        now = 15_000
        assertEquals(10, timer.speechSeconds())
        assertEquals(15, timer.sessionSeconds())
    }

    @Test
    fun resetZeraOsDoisTempos() {
        var now = 0L
        val timer = SpeechSessionTimer { now }
        timer.start()
        timer.beginSpeech()
        now = 10_000
        timer.reset()
        assertEquals(0, timer.speechSeconds())
        assertEquals(0, timer.sessionSeconds())
        assertFalse(timer.isRunning)
    }

    @Test
    fun pausarDuasVezesNaoSomaTempoDuplicado() {
        var now = 0L
        val timer = SpeechSessionTimer { now }
        timer.start()
        timer.beginSpeech()
        now = 10_000
        timer.pause()
        timer.pause()
        now = 20_000
        assertEquals(10, timer.speechSeconds())
        assertEquals(20, timer.sessionSeconds())
    }
}
