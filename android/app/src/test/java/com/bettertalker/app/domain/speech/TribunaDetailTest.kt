package com.bettertalker.app.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.1 — faixas editoriais dos três níveis de detalhe. Puro. */
class TribunaDetailTest {

    @Test
    fun compactoPedePoucosLembretesCurtos() {
        val r = TribunaDetail.COMPACT.rules()
        assertEquals(3, r.minCues)
        assertEquals(5, r.maxCues)
        assertEquals(12, r.maxWords)
        assertEquals(3, r.minKeyTerms)
        assertEquals(3, r.maxKeyTerms)
    }

    @Test
    fun padraoMantemAFaixaDaV42() {
        val r = TribunaDetail.STANDARD.rules()
        assertEquals(4, r.minCues)
        assertEquals(8, r.maxCues)
        assertEquals(20, r.maxWords)
        assertEquals(3, r.minKeyTerms)
        assertEquals(6, r.maxKeyTerms)
    }

    @Test
    fun detalhadoAumentaAGranularidade() {
        val r = TribunaDetail.DETAILED.rules()
        assertEquals(8, r.minCues)
        assertEquals(12, r.maxCues)
        assertEquals(25, r.maxWords)
        assertEquals(5, r.minKeyTerms)
        assertEquals(8, r.maxKeyTerms)
    }

    @Test
    fun faixasSaoCoerentesEEscalamComODetalhe() {
        val all = TribunaDetail.values().map { it.rules() }
        all.forEach { r ->
            assertTrue(r.minCues in 1..r.maxCues)
            assertTrue(r.minKeyTerms in 1..r.maxKeyTerms)
            assertTrue(r.maxWords > 0)
        }
        assertEquals(all.map { it.maxCues }.sorted(), all.map { it.maxCues })
        assertEquals(all.map { it.maxWords }.sorted(), all.map { it.maxWords })
    }
}
