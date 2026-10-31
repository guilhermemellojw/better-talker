package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2.6 — fallback por contexto: threshold, sem trecho aleatório. */
class ContextMatcherTest {

    private val passage =
        "O tempo passa muito rápido e a vida parece muito curta. " +
            "Temos o desejo de continuar vivendo e nunca morrer."

    @Test
    fun contextoForte_encontraPassagem() {
        val match = bestContextMatch(
            listOf(
                "Texto sem relação com a frase.",
                passage,
                "Outro texto qualquer sobre oratória.",
            ),
            "O tempo passa muito rápido e a vida parece muito curta. " +
                "Temos o desejo de continuar vivendo e nunca morrer.",
        )
        assertTrue(match != null)
        assertEquals(1, match!!.index)
        assertTrue(match.matchedTerms >= 3)
        assertTrue(match.ratio >= 0.25)
    }

    @Test
    fun contextoFraco_naoDevolveTrecho() {
        assertNull(
            bestContextMatch(
                listOf("Texto sem relação com a frase."),
                "O tempo passa muito rápido e a vida parece muito curta.",
            ),
        )
    }

    @Test
    fun contextoCurto_naoTemTermosSuficientes() {
        assertNull(bestContextMatch(listOf("vida eterna"), "vida eterna"))
    }

    @Test
    fun stopwordsNaoContamComoContexto() {
        val terms = contextTerms("O tempo passa muito rápido e a vida parece muito curta.")
        assertTrue(terms.contains("tempo"))
        assertTrue(terms.contains("vida"))
        assertTrue(terms.none { it == "muito" || it == "para" || it == "como" })
    }

    @Test
    fun janelaJuntaTrechosConsecutivos() {
        val match = bestContextWindow(
            listOf(
                "Texto irrelevante.",
                "O tempo passa muito rápido.",
                "A vida parece muito curta.",
            ),
            "O tempo passa muito rápido e a vida parece muito curta.",
            window = 2,
        )
        assertTrue(match != null)
        assertEquals(1, match!!.index)
        assertTrue(match.matchedTerms >= 3)
    }
}
