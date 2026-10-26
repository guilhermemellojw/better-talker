package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1 — proposta de edição: orçamento pelo foco + nº de operações. */
class ProposalBudgetTest {

    @Test
    fun focoVazio_ficaNoPiso1500() {
        assertEquals(1500, OutputBudget.proposal(0))
        assertEquals(1500, OutputBudget.proposal(100))
    }

    @Test
    fun focoMedio_escalaComOsChars() {
        // 2.000 chars ≈ 500 tokens + 400 de JSON + 700 de envelope = 1.600.
        assertEquals(1600, OutputBudget.proposal(2000))
    }

    @Test
    fun maisOperacoes_aumentamOOrcamento() {
        // 5.000 chars ≈ 1.250 tokens por operação.
        assertEquals(4000, OutputBudget.proposal(5000, operations = 2))
        assertEquals(1975, OutputBudget.proposal(100, operations = 3))
    }

    @Test
    fun focoGigante_naoPassaDe4096() {
        assertEquals(4096, OutputBudget.proposal(12000))
        assertEquals(4096, OutputBudget.proposal(12000, operations = 3))
        assertEquals(4096, OutputBudget.proposal(50000, operations = 10))
    }

    @Test
    fun operacoesNegativas_viramUma() {
        assertEquals(OutputBudget.proposal(2000, 1), OutputBudget.proposal(2000, 0))
        assertEquals(OutputBudget.proposal(2000, 1), OutputBudget.proposal(2000, -3))
    }

    @Test
    fun faixaSempreDentroDoCaminho() {
        listOf(0, 1, 100, 2000, 5000, 12000, 50000).forEach { chars ->
            val b = OutputBudget.proposal(chars)
            assertTrue("chars=$chars budget=$b", b in 1500..4096)
        }
    }
}
