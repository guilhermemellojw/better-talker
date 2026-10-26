package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 — estrutura: orçamento por minutos/seções e divisão acima de 30 min.
 * Nenhuma resposta única acima do teto. Puro/testável.
 */
class StructureBudgetTest {

    @Test
    fun estruturaCurta_ficaNoPiso4096() {
        val b = OutputBudget.structure(totalMinutes = 5, sectionCount = 1)
        assertEquals(4096, b.maxTokens)
        assertEquals(1, b.parts)
    }

    @Test
    fun quinzeMinutos_tambemRespeitaOPiso() {
        val b = OutputBudget.structure(totalMinutes = 15, sectionCount = 3)
        assertEquals(4096, b.maxTokens)
        assertEquals(1, b.parts)
    }

    @Test
    fun trintaMinutos_usaOOrcamentoCalculadoSemDividir() {
        // 3.900 palavras × 1,5 + 200 = 6.050.
        val b = OutputBudget.structure(totalMinutes = 30, sectionCount = 5)
        assertEquals(6050, b.maxTokens)
        assertEquals(1, b.parts)
        assertEquals(6050, b.rawTokens)
    }

    @Test
    fun trintaEUmMinutos_divideEmDuasPartes() {
        // 31 min = 4.030 palavras → 6.245 tokens brutos; 2 partes de 4.096.
        val b = OutputBudget.structure(totalMinutes = 31, sectionCount = 5)
        assertEquals(2, b.parts)
        assertEquals(4096, b.maxTokens)
    }

    @Test
    fun quarentaECincoMinutos_divideEmDuasPartes() {
        // 45 min = 5.850 palavras → 8.975 tokens brutos → 2 partes de 4.488.
        val b = OutputBudget.structure(totalMinutes = 45, sectionCount = 5)
        assertEquals(2, b.parts)
        assertEquals(4488, b.maxTokens)
    }

    @Test
    fun noventaMinutos_divideEmTresPartes() {
        // 90 min = 11.700 palavras → 17.750 tokens brutos → 3 partes de 5.917.
        val b = OutputBudget.structure(totalMinutes = 90, sectionCount = 9)
        assertEquals(3, b.parts)
        assertEquals(5917, b.maxTokens)
    }

    @Test
    fun semMinutos_usaONumeroDeSecoes() {
        val b = OutputBudget.structure(totalMinutes = null, sectionCount = 6)
        assertEquals(4096, b.maxTokens)
        assertEquals(1, b.parts)
    }

    @Test
    fun esbocoColadoGigante_tambemDivide() {
        val b = OutputBudget.structure(
            totalMinutes = null,
            sectionCount = 1,
            sourceChars = 60_000,
        )
        assertEquals(3, b.parts)
        assertTrue(b.maxTokens in 4096..8192)
    }

    @Test
    fun splitDeTitulos_preservaTodosNaOrdem() {
        val titles = listOf("A", "B", "C", "D", "E")
        val groups = OutputBudget.splitStructureTitles(titles, 2)
        assertEquals(2, groups.size)
        assertEquals(listOf("A", "B", "C"), groups[0])
        assertEquals(listOf("D", "E"), groups[1])
        assertEquals(titles, groups.flatten())
    }

    @Test
    fun splitComMenosTitulosQuePartes_naoCriaParteVazia() {
        assertEquals(
            listOf(listOf("Único")),
            OutputBudget.splitStructureTitles(listOf("Único"), 3),
        )
        assertTrue(OutputBudget.splitStructureTitles(emptyList(), 2).isEmpty())
    }
}
