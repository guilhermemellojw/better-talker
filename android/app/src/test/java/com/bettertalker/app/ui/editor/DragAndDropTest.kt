package com.bettertalker.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #7d4 — testes puros do alvo do D&D de seções (sem Compose).
 */
class DragAndDropTest {

    private val ids = listOf("A", "B", "C")
    private val ys = mapOf("A" to 0f, "B" to 200f, "C" to 400f)

    @Test
    fun alvo_entreBEC_maisProximoDeC() {
        assertEquals(2, computeTargetIndex(350f, ids, ys, "A"))
    }

    @Test
    fun alvo_semMover_ficaNoProprioIndice() {
        assertEquals(0, computeTargetIndex(0f, ids, ys, "A"))
    }

    @Test
    fun alvo_alemDoFim_clampeiaNoUltimo() {
        assertEquals(2, computeTargetIndex(1000f, ids, ys, "A"))
    }

    @Test
    fun alvo_acimaDoInicio_clampeiaNoZero() {
        assertEquals(0, computeTargetIndex(-100f, ids, ys, "B"))
    }

    @Test
    fun alvo_semDados_retornaNull() {
        assertNull(computeTargetIndex(100f, listOf("A"), emptyMap(), "A"))
    }
}
