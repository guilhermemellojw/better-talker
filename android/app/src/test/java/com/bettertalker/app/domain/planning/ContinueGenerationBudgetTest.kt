package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C1 — "Continuar gerando": o orçamento olha o que falta da meta, não o
 * tamanho da resposta anterior. Puro/testável.
 */
class ContinueGenerationBudgetTest {

    private fun demoCard(turns: Int): String =
        List(turns) { "<p><b>Papel:</b> fala</p>" }.joinToString("") { it }

    private fun words(count: Int): String = "palavra ".repeat(count)

    @Test
    fun respostaCurtaSemMeta_usaOPiso() {
        assertEquals(1024, OutputBudget.continueGeneration("Resposta curta."))
        assertEquals(1024, OutputBudget.continueGeneration(""))
    }

    @Test
    fun respostaLongaSemMeta_usaOTamanhoAtualComoSinal() {
        // 6.000 chars ≈ 2.000 tokens + 512 de folga.
        assertEquals(2512, OutputBudget.continueGeneration("x".repeat(6000)))
    }

    @Test
    fun discursoComMeta_usaAsPalavrasRestantes() {
        // Meta 650; nada produzido → 650×1,5 + 200 = 1.175.
        assertEquals(1175, OutputBudget.continueGeneration("", targetWords = 650))
        // Meta 3.900 com 1.000 palavras → 2.900 restantes → 4.550.
        assertEquals(
            4550,
            OutputBudget.continueGeneration(words(1000), targetWords = 3900),
        )
    }

    @Test
    fun demoComMeta_usaAsReplicasRestantes() {
        // Meta 75 réplicas com 10 feitas → 65×80 + 200 = 5.400.
        assertEquals(
            5400,
            OutputBudget.continueGeneration(demoCard(10), targetTurns = 75),
        )
        // Meta 10 com 0 feitas → 1.000 → piso 1.024.
        assertEquals(1024, OutputBudget.continueGeneration("", targetTurns = 10))
    }

    @Test
    fun metaJaAtingida_aindaTemPisoParaContinuar() {
        assertEquals(
            1024,
            OutputBudget.continueGeneration(words(1000), targetWords = 650),
        )
    }

    @Test
    fun metaGigante_naoPassaDoTeto() {
        assertEquals(
            8192,
            OutputBudget.continueGeneration("", targetWords = 11700),
        )
    }
}
