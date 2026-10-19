package com.bettertalker.app.data.planning

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0 (alinhamento persona/tempo): orçamento de saída do draft proporcional à
 * meta de palavras da seção (~130 palavras/min; ~1,45 tokens/palavra + folga
 * do JSON), com piso 500 e teto 1280 (OTPM). Puro/testável.
 */
class SectionDraftBudgetTest {

    @Test
    fun piso500_paraSecoesCurtas() {
        assertEquals(500, draftOutputBudget(null))
        assertEquals(500, draftOutputBudget(0))
        assertEquals(500, draftOutputBudget(1))
        assertEquals(500, draftOutputBudget(2))
    }

    @Test
    fun escalaComOsMinutos() {
        // 3 min → 390 palavras → 390×1,45 + 120 = 685.
        assertEquals(685, draftOutputBudget(3))
        // 5 min → 650 palavras → 1062.
        assertEquals(1062, draftOutputBudget(5))
    }

    @Test
    fun teto1280_paraSecoesLongas() {
        assertEquals(1280, draftOutputBudget(7))
        assertEquals(1280, draftOutputBudget(10))
        assertEquals(1280, draftOutputBudget(30))
    }
}
