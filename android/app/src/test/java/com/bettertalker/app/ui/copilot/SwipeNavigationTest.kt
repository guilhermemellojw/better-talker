package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #25-fix-c: swipe horizontal do chat — decisão pura.
 * Esquerda além do threshold avança; direita volta; resto não faz nada.
 */
class SwipeNavigationTest {

    @Test
    fun esquerdaAlemDoThreshold_avanca() {
        assertEquals(SwipeAction.Next, swipeTarget(-150f, 120f))
    }

    @Test
    fun direitaAlemDoThreshold_volta() {
        assertEquals(SwipeAction.Previous, swipeTarget(150f, 120f))
    }

    @Test
    fun abaixoDoThreshold_nada() {
        assertEquals(SwipeAction.None, swipeTarget(50f, 120f))
    }

    @Test
    fun limiteExato_naoDispara() {
        // Estritamente menor/maior que o threshold — 120f exato = None.
        assertEquals(SwipeAction.None, swipeTarget(-120f, 120f))
        assertEquals(SwipeAction.None, swipeTarget(120f, 120f))
    }

    @Test
    fun semArrasto_nada() {
        assertEquals(SwipeAction.None, swipeTarget(0f, 120f))
    }
}
