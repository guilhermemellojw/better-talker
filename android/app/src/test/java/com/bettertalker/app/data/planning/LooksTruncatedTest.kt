package com.bettertalker.app.data.planning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heurística de corte ([looksTruncated]). Pura, sem LLM/Room.
 */
class LooksTruncatedTest {

    @Test
    fun looksTruncated_finishReasonLength_returnsTrue() {
        assertTrue(looksTruncated("qualquer coisa", "length"))
    }

    @Test
    fun looksTruncated_semPontuacaoTerminal_returnsTrue() {
        assertTrue(looksTruncated("texto sem fim", "stop"))
    }

    @Test
    fun looksTruncated_comPontoFinal_returnsFalse() {
        assertFalse(looksTruncated("texto completo.", "stop"))
    }

    @Test
    fun looksTruncated_comInterrogacao_returnsFalse() {
        assertFalse(looksTruncated("pergunta?", "stop"))
    }

    @Test
    fun looksTruncated_stringVazia_returnsTrue() {
        assertTrue(looksTruncated("", null))
    }

    @Test
    fun looksTruncated_comCJKNoFinal_returnsTrue() {
        // Caso real do reteste: modelo parou em "isso é a 牢".
        assertTrue(looksTruncated("isso é a 牢", "stop"))
    }

    @Test
    fun looksTruncated_htmlTagsIgnored_returnsFalse() {
        // textHtml termina em </p> — as tags não contam como fim.
        assertFalse(looksTruncated("<p>texto completo.</p>", "stop"))
    }
}
