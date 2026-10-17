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
            // #25-fix-b: o corte agora sinaliza a interrupção com [...].
            "Primeira frase completa com contexto suficiente para passar do limite. [...]",
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
        // #25-fix-b: corte sinalizado com [...].
        assertEquals("Texto completo com ponto. [...]", truncateIfCut(text, "length"))
    }

    @Test
    fun truncateIfCut_exatamente100_naoTrunca() {
        val text = "z".repeat(100)
        assertEquals(text, truncateIfCut(text, "stop"))
    }

    // ---------- #25-fix-b: corte curador (sintaxe pendente fechada) ----------

    @Test
    fun truncateIfCut_healsUnclosedMarkdown() {
        val text = "Este é um texto longo o suficiente para passar do limite mínimo de corte " +
            "e que foi interrompido no meio de uma **palavra importante e sem fechar."
        assertTrue(text.length > 100)
        val truncated = truncateIfCut(text, "length")

        assertTrue(truncated.endsWith("** [...]"))
        assertTrue(truncated.contains("importante e sem fechar.**"))
    }

    @Test
    fun truncateIfCut_fechaMarcadoresDeAnotacaoAbertos() {
        val text = "Uma resposta longa o bastante para o corte entrar em ação no meio dela " +
            "com uma sugestão aberta: 〈sugestão〉fale de alguém que sobe uma escada longa " +
            "e continua subindo 〈decisão〉e vamos usar o gancho na abertura."
        assertTrue(text.length > 100)
        val truncated = truncateIfCut(text, "length")

        // Fecha a sugestão (e a decisão) antes do sinal de interrupção.
        assertTrue(truncated.contains("〈/sugestão〉"))
        assertTrue(truncated.contains("〈/decisão〉"))
        assertTrue(truncated.endsWith("[...]"))
        // Sem marcador aberto sobrando.
        assertTrue(markerCount(truncated, "〈sugestão〉") <= markerCount(truncated, "〈/sugestão〉"))
    }

    @Test
    fun markerCount_contaOcorrencias() {
        assertEquals(0, markerCount("texto limpo", "**"))
        assertEquals(2, markerCount("a **b** c", "**"))
        assertEquals(1, markerCount("〈sugestão〉x", "〈sugestão〉"))
    }
}
