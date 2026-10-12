package com.bettertalker.app.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Modo Palco v1 — helpers puros do cronômetro e da fonte de leitura.
 * Sem Compose/Room: só as funções de formatação e faixas de meta.
 */
class StageTimingTest {

    @Test
    fun formatClockFormataMinutosESegundos() {
        assertEquals("00:00", formatClock(0))
        assertEquals("01:05", formatClock(65))
        assertEquals("59:59", formatClock(3599))
        assertEquals("61:01", formatClock(3661))
        assertEquals("00:00", formatClock(-5))
    }

    @Test
    fun etapaDoTimerPorFracaoDaMeta() {
        val target = 10 // 600 segundos
        assertEquals(StageTimerState.GREEN, stageTimerState(299, target)) // 49%
        assertEquals(StageTimerState.AMBER, stageTimerState(300, target)) // 50%
        assertEquals(StageTimerState.AMBER, stageTimerState(479, target)) // 79%
        assertEquals(StageTimerState.RED, stageTimerState(480, target)) // 80%
        assertEquals(StageTimerState.RED, stageTimerState(900, target)) // excedido
    }

    @Test
    fun semMetaNaoTemEstadoDeCor() {
        assertEquals(StageTimerState.IDLE, stageTimerState(0, 0))
        assertEquals(StageTimerState.IDLE, stageTimerState(5000, -1))
    }

    @Test
    fun restanteEExcedidoSaoClampados() {
        assertEquals(600L, stageRemainingSeconds(0, 10))
        assertEquals(0L, stageRemainingSeconds(600, 10))
        assertEquals(0L, stageRemainingSeconds(900, 10))
        assertEquals(0L, stageOvertimeSeconds(600, 10))
        assertEquals(300L, stageOvertimeSeconds(900, 10))
    }

    @Test
    fun fonteDoPalcoClampada() {
        assertEquals(16, clampStageFontSp(10))
        assertEquals(24, clampStageFontSp(24))
        assertEquals(48, clampStageFontSp(100))
    }

    @Test
    fun subtituloDoRelogioMostraMetaRestanteEExcedido() {
        assertEquals("sem meta", stageClockSubtitle(30, 0, running = true))
        assertEquals("sem meta · pausado", stageClockSubtitle(30, 0, running = false))
        assertEquals("meta 10:00 · restam 09:30", stageClockSubtitle(30, 10, running = true))
        assertEquals("meta 10:00 · excedeu 00:05", stageClockSubtitle(605, 10, running = true))
    }
}
