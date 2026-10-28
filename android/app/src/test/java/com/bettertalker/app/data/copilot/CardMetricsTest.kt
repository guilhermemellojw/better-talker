package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Fase 3 — telemetria só com metadados, sem texto. */
class CardMetricsTest {

    @Test
    fun emittedTemContratoDaFase3() {
        assertEquals(
            "card|emitted|route=CHAT|target=SECTION|source=PUSH|chars=3191|cards=1",
            CardMetrics.emittedLine("CHAT", "SECTION", "PUSH", 3191, 1),
        )
    }

    @Test
    fun appliedReplacedStaleNaoMisturamTexto() {
        assertEquals(
            "card|applied|action=APPEND|stale=false|undo=true|chars=3942",
            CardMetrics.appliedLine("APPEND", stale = false, undo = true, chars = 3942),
        )
        assertEquals(
            "card|replaced|confirmed=true|stale=false|chars=3942",
            CardMetrics.replacedLine(confirmed = true, stale = false, chars = 3942),
        )
        assertEquals(
            "card|stale|action=APPEND|chars=3942",
            CardMetrics.staleLine("APPEND", 3942),
        )
    }

    @Test
    fun linhasNaoVazamTextoDoUsuarioOuDoCard() {
        val lines = listOf(
            CardMetrics.emittedLine("CHAT", "SECTION", "PUSH", 3, 1),
            CardMetrics.appliedLine("APPEND", false, true, 3),
            CardMetrics.replacedLine(true, false, 3),
            CardMetrics.staleLine("DETECTED", 0),
        )
        lines.forEach {
            assertFalse(it.contains("<texto-do-card>"))
            assertFalse(it.contains("Você já parou"))
        }
    }
}
