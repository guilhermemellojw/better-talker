package com.bettertalker.app.data.planning

import org.junit.Assert.assertEquals
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

    @Test
    fun truncateIfCut_textoCurtoSemPontuacao_naoTrunca() {
        assertEquals("Sim", truncateIfCut("Sim", "stop"))
    }

    @Test
    fun truncateIfCut_textoLongoSemPontuacao_truncaAteUltimaFrase() {
        val text = "Primeira frase completa com contexto suficiente para passar do limite. " +
            "Segunda parte que foi cortada no meio da palavra sust"
        assertTrue(text.length > 100)
        assertEquals(
            "Primeira frase completa com contexto suficiente para passar do limite.",
            truncateIfCut(text, "stop")
        )
    }

    @Test
    fun truncateIfCut_textoLongoComPontuacao_naoTrunca() {
        val text = "Uma resposta longa e completa que termina corretamente com ponto final. " +
            "Ela tem mais de cem caracteres no total para passar do limite mínimo."
        assertTrue(text.length > 100)
        assertEquals(text, truncateIfCut(text, "stop"))
    }

    @Test
    fun truncateIfCut_semNenhumaPontuacao_retornaOriginal() {
        val text = "x".repeat(150)
        assertEquals(text, truncateIfCut(text, "stop"))
    }

    @Test
    fun truncateIfCut_finishReasonLength_truncaMesmoComPontuacao() {
        val text = "Texto completo com ponto. " + "y".repeat(120)
        assertEquals("Texto completo com ponto.", truncateIfCut(text, "length"))
    }

    @Test
    fun truncateIfCut_exatamente100_naoTrunca() {
        val text = "z".repeat(100)
        assertEquals(text, truncateIfCut(text, "stop"))
    }
}
