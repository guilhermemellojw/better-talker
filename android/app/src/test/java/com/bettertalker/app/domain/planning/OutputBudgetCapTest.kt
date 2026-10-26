package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 — nenhum caminho passa do teto e todos têm piso explícito.
 * Puro/testável.
 */
class OutputBudgetCapTest {

    @Test
    fun tetoAbsolutoEh8192() {
        assertEquals(8192, OutputBudget.MAX)
        assertEquals(1024, OutputBudget.CONTINUE_MIN)
    }

    @Test
    fun chatCurtoContinuaCurto() {
        assertEquals(2048, OutputBudget.chat(hasResolvedRefs = false))
        assertEquals(2048, OutputBudget.chat(hasResolvedRefs = false))
    }

    @Test
    fun chatComFontes_temFolga() {
        assertEquals(4096, OutputBudget.chat(hasResolvedRefs = true))
    }

    @Test
    fun oratoriaEscalaComAMinutagem() {
        assertEquals(1280, OutputBudget.oratory(focusChars = 0, minutes = 5))
        assertEquals(2150, OutputBudget.oratory(focusChars = 0, minutes = 10))
        assertEquals(4096, OutputBudget.oratory(focusChars = 0, minutes = 30))
        // Sem duração, o foco manda: 3.000 chars → 1.000 + 600 = 1.600.
        assertEquals(1600, OutputBudget.oratory(focusChars = 3000, minutes = null))
        // Sem foco e sem duração → piso.
        assertEquals(1280, OutputBudget.oratory(focusChars = 0, minutes = null))
    }

    @Test
    fun esbocoInicial_continuaEm1500() {
        assertEquals(1500, OutputBudget.outline())
        assertEquals(1500, OutputBudget.outline(totalMinutes = 45, sectionCount = 9))
    }

    @Test
    fun tribunaCresceComAsSecoesSemEstourar() {
        assertEquals(2048, OutputBudget.tribuna(0))
        assertEquals(2448, OutputBudget.tribuna(5))
        assertEquals(4096, OutputBudget.tribuna(100))
    }

    @Test
    fun nenhumCaminhoPassaDoTeto() {
        val samples = listOf(
            OutputBudget.chat(false),
            OutputBudget.chat(true),
            OutputBudget.continueGeneration("x".repeat(100_000), targetWords = 11700),
            OutputBudget.proposal(100_000, operations = 10),
            OutputBudget.structure(90, 20, 100_000).maxTokens,
            OutputBudget.minimal(100_000),
            OutputBudget.oratory(100_000, minutes = 90),
            OutputBudget.outline(90, 20),
            OutputBudget.sectionDraft(90),
            OutputBudget.tribuna(500),
        )
        samples.forEach { b ->
            assertTrue("budget=$b", b in 1..OutputBudget.MAX)
        }
    }

    @Test
    fun longTextMantemAsFaixasValidadas() {
        fun request(minutes: Int) = LongTextRequest(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.SECTION,
            minutes = minutes,
            sectionMinutes = minutes,
        )
        assertEquals(2048, OutputBudget.longText(request(5)).maxTokens)
        assertEquals(6050, OutputBudget.longText(request(30)).maxTokens)
        assertEquals(2, OutputBudget.longText(request(45)).parts)
    }
}
