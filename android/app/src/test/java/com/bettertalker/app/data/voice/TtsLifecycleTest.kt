package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Fase 1 — ownership de TTS: o contador de telas nunca derruba o engine. */
class TtsLifecycleTest {

    @Before
    fun setUp() {
        TtsLifecycle.resetForTest()
    }

    @After
    fun tearDown() {
        TtsLifecycle.resetForTest()
    }

    @Test
    fun refCountSobeEDesceSemNegativo() {
        assertEquals(0, TtsLifecycle.activeUsers)
        TtsLifecycle.acquire()
        assertEquals(1, TtsLifecycle.activeUsers)
        TtsLifecycle.acquire()
        assertEquals(2, TtsLifecycle.activeUsers)
        TtsLifecycle.release()
        assertEquals(1, TtsLifecycle.activeUsers)
        TtsLifecycle.release()
        assertEquals(0, TtsLifecycle.activeUsers)
        // Release extra (onDispose duplicado/rotação) não fica negativo.
        TtsLifecycle.release()
        assertEquals(0, TtsLifecycle.activeUsers)
    }

    @Test
    fun acquireDepoisDeZeroVoltaAFuncionar() {
        TtsLifecycle.acquire()
        TtsLifecycle.release()
        assertEquals(0, TtsLifecycle.activeUsers)
        TtsLifecycle.acquire()
        assertEquals(1, TtsLifecycle.activeUsers)
        TtsLifecycle.release()
    }
}
