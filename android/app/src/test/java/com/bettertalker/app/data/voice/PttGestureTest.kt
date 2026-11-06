package com.bettertalker.app.data.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Limiar do arrasto que cancela o PTT (para cima ou para a esquerda). */
class PttGestureTest {

    @Test
    fun arrastarParaCimaOuEsquerdaCancela() {
        assertTrue(isPttCancelDrag(dx = 0f, dy = -80f, threshold = 72f))
        assertTrue(isPttCancelDrag(dx = -80f, dy = 0f, threshold = 72f))
        assertTrue(isPttCancelDrag(dx = -60f, dy = -60f, threshold = 72f))
    }

    @Test
    fun moverParaBaixoOuDireitaNaoCancela() {
        assertFalse(isPttCancelDrag(dx = 80f, dy = 0f, threshold = 72f))
        assertFalse(isPttCancelDrag(dx = 0f, dy = 80f, threshold = 72f))
        assertFalse(isPttCancelDrag(dx = -10f, dy = -10f, threshold = 72f))
    }
}
