package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase A — ritmo de fala único (130 palavras/min; réplicas de demo). */
class SpeechPacingTest {

    @Test
    fun targetWords_10Minutos_sao1300() {
        assertEquals(1300, SpeechPacing.targetWords(10))
    }

    @Test
    fun targetWords_minimoDe1Minuto() {
        assertEquals(130, SpeechPacing.targetWords(0))
        assertEquals(130, SpeechPacing.targetWords(-3))
    }

    @Test
    fun targetTurns_4Minutos_ficaNaFaixaDe8a12() {
        val turns = SpeechPacing.targetTurns(4)
        assertTrue("turns=$turns", turns in 8..12)
        assertEquals(10, turns)
    }

    @Test
    fun targetTurns_nuncaZero() {
        assertTrue(SpeechPacing.targetTurns(0) >= 2)
    }
}
