package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.LongTextKind
import com.bettertalker.app.domain.planning.OutputBudget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C1 — orçamento do draft proporcional ao tempo da SEÇÃO (ou do sub-ponto,
 * quando a seção longa foi dividida), piso 2048 e teto 8192. Puro/testável.
 */
class SectionDraftBudgetTest {

    @Test
    fun piso500_apenasSemTempo() {
        assertEquals(500, OutputBudget.sectionDraft(null))
        assertEquals(500, OutputBudget.sectionDraft(0))
    }

    @Test
    fun secoesCurtas_ficamNoPiso2048() {
        assertEquals(2048, OutputBudget.sectionDraft(1))
        assertEquals(2048, OutputBudget.sectionDraft(2))
        assertEquals(2048, OutputBudget.sectionDraft(3))
        assertEquals(2048, OutputBudget.sectionDraft(5))
    }

    @Test
    fun escalaComOsMinutos() {
        // 10 min → 1300 palavras → 1300×1,5 + 200 = 2150.
        assertEquals(2150, OutputBudget.sectionDraft(10))
        // 15 min → 1950 palavras → 3125.
        assertEquals(3125, OutputBudget.sectionDraft(15))
        // 20 min → 2600 palavras → 4100.
        assertEquals(4100, OutputBudget.sectionDraft(20))
        // 30 min → 3900 palavras → 6050.
        assertEquals(6050, OutputBudget.sectionDraft(30))
    }

    @Test
    fun teto8192_paraSecoesMuitoLongas() {
        assertEquals(8192, OutputBudget.sectionDraft(45))
        assertEquals(8192, OutputBudget.sectionDraft(60))
    }

    @Test
    fun demo_usaReplicasPorMinuto() {
        // 4 min → 10 réplicas → 10×80 + 200 = 1000 → piso 2048.
        assertEquals(2048, OutputBudget.sectionDraft(4, LongTextKind.DEMO))
        // 10 min → 25 réplicas → 2200.
        assertEquals(2200, OutputBudget.sectionDraft(10, LongTextKind.DEMO))
        // 30 min → 75 réplicas → 6200.
        assertEquals(6200, OutputBudget.sectionDraft(30, LongTextKind.DEMO))
    }

    @Test
    fun subpontoDeSecaoLonga_usaAFatiaDoTempo() {
        assertEquals(5, effectiveDraftMinutes(30, isSubPoint = true, subPointCount = 6))
        assertEquals(4, effectiveDraftMinutes(30, isSubPoint = true, subPointCount = 7))
        assertEquals(30, effectiveDraftMinutes(30, isSubPoint = false, subPointCount = 6))
        assertEquals(30, effectiveDraftMinutes(30, isSubPoint = true, subPointCount = 1))
        assertEquals(1, effectiveDraftMinutes(1, isSubPoint = true, subPointCount = 4))
    }
}
