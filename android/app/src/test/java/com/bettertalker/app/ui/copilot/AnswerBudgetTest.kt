package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.llm.CHAT_MAX_OUTPUT_TOKENS
import com.bettertalker.app.data.llm.CHAT_MAX_OUTPUT_TOKENS_WITH_SOURCES
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
        // exp/chat-quality (2026-10-21): 1000 cortava a resposta no meio
        // (37 chars, finish=length) — a base do chat sobe para 2048.
        assertEquals(CHAT_MAX_OUTPUT_TOKENS, answerOutputBudget(hasResolvedRefs = false))
        assertEquals(2048, answerOutputBudget(hasResolvedRefs = false))
    }

    @Test
    fun comRefsResolvidas_usaTetoMaior() {
        assertEquals(
            CHAT_MAX_OUTPUT_TOKENS_WITH_SOURCES,
            answerOutputBudget(hasResolvedRefs = true),
        )
    }
}
