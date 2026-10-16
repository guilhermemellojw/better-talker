package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.OratoryGeneration.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/** #16-fix-c — limite de palavras pela duração real da seção (130/min). */
class OratoryMaxWordsTest {

    @Test
    fun desenvolvimentoDe6MinutosTem780() {
        assertEquals(780, OratoryGeneration.maxWords(Mode.DEVELOPMENT, 6))
    }

    @Test
    fun aberturaDe1MinutoTem130() {
        assertEquals(130, OratoryGeneration.maxWords(Mode.INTRODUCTION, 1))
    }

    @Test
    fun tetoDeSegurancaEm2000() {
        assertEquals(2000, OratoryGeneration.maxWords(Mode.DEVELOPMENT, 100))
    }
}
