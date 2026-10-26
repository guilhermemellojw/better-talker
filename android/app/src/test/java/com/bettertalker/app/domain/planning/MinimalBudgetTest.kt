package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 — conversa sobre esboço colado: 2.048–4.096. Texto completo não é
 * gerado aqui; o chamador encaminha ao DraftSheet. Puro/testável.
 */
class MinimalBudgetTest {

    @Test
    fun esbocoCurto_ficaNoPiso2048() {
        assertEquals(2048, OutputBudget.minimal(0))
        assertEquals(2048, OutputBudget.minimal(15))
        // 2.000 chars → 2.048 + 125 = 2.173.
        assertEquals(2173, OutputBudget.minimal(2000))
    }

    @Test
    fun esbocoLongo_escalaAteOTeto() {
        // 16.000 chars → 2.048 + 1.000 = 3.048.
        assertEquals(3048, OutputBudget.minimal(16000))
        // 40.000 chars → 2.048 + 2.500 = 4.548 → teto 4.096.
        assertEquals(4096, OutputBudget.minimal(40000))
    }

    @Test
    fun pedidoDeTextoCompleto_usaOTetoDeSeguranca() {
        assertEquals(4096, OutputBudget.minimal(0, asksForFullText = true))
        assertEquals(4096, OutputBudget.minimal(40000, asksForFullText = true))
    }

    @Test
    fun faixaSempreDentroDoCaminho() {
        listOf(0, 1, 2000, 16000, 40000, 100000).forEach { chars ->
            val b = OutputBudget.minimal(chars)
            assertTrue("chars=$chars budget=$b", b in 2048..4096)
        }
    }
}
