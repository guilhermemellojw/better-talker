package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.INSUFFICIENT_EVIDENCE_MESSAGE
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2.4 — o bloco de grounding do Gemma local deve reforçar ADESÃO às fontes
 * (desenvolver a partir da evidência) sem perder a frase de insuficiência.
 */
class F24AdherencePromptTest {

    @Test
    fun groundingBlock_hasAdherenceRule() {
        assertTrue(GEMMA_GROUNDING_BLOCK.contains("ADESÃO"))
        assertTrue(GEMMA_GROUNDING_BLOCK.contains("DESENVOLVA a resposta a partir delas"))
        assertTrue(GEMMA_GROUNDING_BLOCK.contains("integre a evidência"))
    }

    @Test
    fun groundingBlock_allowsCreativeFormWithoutExternalFacts() {
        assertTrue(GEMMA_GROUNDING_BLOCK.contains("criatividade é de FORMA"))
        assertTrue(GEMMA_GROUNDING_BLOCK.contains("conteúdo factual continua vindo das fontes"))
    }

    @Test
    fun groundingBlock_keepsInsufficientMessage() {
        assertTrue(GEMMA_GROUNDING_BLOCK.contains(INSUFFICIENT_EVIDENCE_MESSAGE))
    }

    @Test
    fun groundingBlock_keepsNoInventRule() {
        assertTrue(GEMMA_GROUNDING_BLOCK.contains("NUNCA crie doutrinas"))
    }
}
