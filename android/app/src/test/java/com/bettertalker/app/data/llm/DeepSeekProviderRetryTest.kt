package com.bettertalker.app.data.llm

import com.bettertalker.app.data.planning.looksTruncated
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #11-fix: retry por corte de orçamento — 1 tentativa (sem loop). O fragmento
 * cortado não-vazio (o stub "O trecho de it Joanã §5,") entra no retry; o
 * provider em si não é testável em JVM (HTTP), então a decisão é pura.
 */
class DeepSeekProviderRetryTest {

    @Test
    fun stubCortado_disparaRetry() {
        val stub = "O trecho de *it* Joana n. 5, §5,"
        assertTrue(looksTruncated(stub, "length"))
        assertTrue(shouldRetryOnLength(emptyByLength = false, cut = true, budgetBumped = false))
    }

    @Test
    fun vazioPorLength_disparaRetry_comportamentoAtualPreservado() {
        assertTrue(shouldRetryOnLength(emptyByLength = true, cut = false, budgetBumped = false))
    }

    @Test
    fun retryJaFeito_naoRepete_semLoop() {
        assertFalse(shouldRetryOnLength(emptyByLength = false, cut = true, budgetBumped = true))
        assertFalse(shouldRetryOnLength(emptyByLength = true, cut = false, budgetBumped = true))
    }

    @Test
    fun finishStop_semRetry() {
        assertFalse(shouldRetryOnLength(emptyByLength = false, cut = false, budgetBumped = false))
        // `cut` exige finish=="length"; texto pontuado com "stop" não acusa corte.
        assertFalse(looksTruncated("Resposta completa e pontuada.", "stop"))
    }
}
