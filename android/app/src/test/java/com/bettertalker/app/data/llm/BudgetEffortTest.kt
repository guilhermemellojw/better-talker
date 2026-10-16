package com.bettertalker.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** #8f — orçamento do turno: "Profundo" sobe o teto default; os demais não. Puro. */
class BudgetEffortTest {

    @Test
    fun profundoSobePara2000() {
        assertEquals(2000, DeepSeekProvider.outputBudgetFor("high", 1000))
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
