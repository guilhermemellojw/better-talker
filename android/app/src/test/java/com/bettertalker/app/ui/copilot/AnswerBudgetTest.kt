package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.llm.DEFAULT_LLM_MAX_OUTPUT_TOKENS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #11-fix: orçamento de saída maior quando a pergunta cita refs do acervo. */
class AnswerBudgetTest {

    @Test
    fun comRefsResolvidas_budgetMaior() {
        assertTrue(answerOutputBudget(hasResolvedRefs = true) >= 2048)
    }

    @Test
    fun semRefs_budgetDefault() {
        assertEquals(DEFAULT_LLM_MAX_OUTPUT_TOKENS, answerOutputBudget(hasResolvedRefs = false))
    }
}
