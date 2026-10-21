package com.bettertalker.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** #8f — orçamento do turno: "Profundo" sobe o teto default; os demais não. Puro. */
class BudgetEffortTest {

    @Test
    fun profundoSobePara2000() {
        // exp/chat-quality (2026-10-21): a 2000 o "high" ainda devolvia content
        // vazio (reasoning consumiu tudo) — o teto do Profundo sobe para 4096.
        assertEquals(4096, DeepSeekProvider.outputBudgetFor("high", 1000))
    }

    @Test
    fun rapidoMantemODefault() {
        assertEquals(1000, DeepSeekProvider.outputBudgetFor("low", 1000))
    }

    @Test
    fun noneMantemODefault() {
        assertEquals(1000, DeepSeekProvider.outputBudgetFor("none", 1000))
    }

    @Test
    fun tetoMaiorExplicitoEhRespeitado() {
        assertEquals(2560, DeepSeekProvider.outputBudgetFor("high", 2560))
    }
}
