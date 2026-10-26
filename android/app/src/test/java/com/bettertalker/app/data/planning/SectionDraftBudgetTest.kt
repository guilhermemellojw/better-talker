package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.LongTextKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bloco de geração longa: orçamento do draft proporcional ao tempo da SEÇÃO
 * (nunca ao total), piso 2048 e teto 8192. Puro/testável.
 */
class SectionDraftBudgetTest {

    @Test
    fun piso500_apenasSemTempo() {
        assertEquals(500, draftOutputBudget(null))
        assertEquals(500, draftOutputBudget(0))
    }

    @Test
    fun secoesCurtas_ficamNoPiso2048() {
        assertEquals(2048, draftOutputBudget(1))
        assertEquals(2048, draftOutputBudget(2))
        assertEquals(2048, draftOutputBudget(3))
        assertEquals(2048, draftOutputBudget(5))
    }

    @Test
    fun escalaComOsMinutos() {
        // 10 min → 1300 palavras → 1300×1,5 + 200 = 2150.
        assertEquals(2150, draftOutputBudget(10))
        // 15 min → 1950 palavras → 3125.
        assertEquals(3125, draftOutputBudget(15))
        // 20 min → 2600 palavras → 4100.
        assertEquals(4100, draftOutputBudget(20))
        // 30 min → 3900 palavras → 6050.
        assertEquals(6050, draftOutputBudget(30))
    }

    @Test
    fun teto8192_paraSecoesMuitoLongas() {
        assertEquals(8192, draftOutputBudget(45))
        assertEquals(8192, draftOutputBudget(60))
    }

    @Test
    fun demo_usaReplicasPorMinuto() {
        // 4 min → 10 réplicas → 10×80 + 200 = 1000 → piso 2048.
        assertEquals(2048, draftOutputBudget(4, LongTextKind.DEMO))
        // 10 min → 25 réplicas → 2200.
        assertEquals(2200, draftOutputBudget(10, LongTextKind.DEMO))
        // 30 min → 75 réplicas → 6200.
        assertEquals(6200, draftOutputBudget(30, LongTextKind.DEMO))
    }
}
