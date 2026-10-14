package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #8c (GAP-2): extração do trecho pedido da última resposta. */
class InsertSnippetExtractionTest {

    @Test
    fun dashBlocks_nthBlock2() {
        val text = "Abertura.\n\n---\n\nMeio do discurso.\n\n---\n\nFecho."
        assertEquals("Meio do discurso.", extractSnippet(text, Snippet.NthBlock(2)))
    }

    @Test
    fun opcaoMarkers_nthBlock2() {
        val text = "**Opção 1:**\nPrimeira abertura.\n\n**Opção 2:**\nSegunda abertura.\n\n" +
            "**Opção 3:**\nTerceira abertura."
        assertEquals("Segunda abertura.", extractSnippet(text, Snippet.NthBlock(2)))
    }

    @Test
    fun numberedLines_nthBlock2() {
        val text = "1. Primeiro ponto.\n2. Segundo ponto.\n3. Terceiro ponto."
        assertEquals("2. Segundo ponto.", extractSnippet(text, Snippet.NthBlock(2)))
    }

    @Test
    fun paragraphs_nthParagraph2() {
        val text = "Primeiro parágrafo.\n\nParágrafo do meio.\n\nÚltimo parágrafo."
        assertEquals("Parágrafo do meio.", extractSnippet(text, Snippet.NthParagraph(2)))
    }

    @Test
    fun between_extraiTrecho() {
        val text = "Antes. O início do trecho aqui. Meio. Fim do trecho. Depois."
        assertEquals(
            "do trecho aqui. Meio.",
            extractSnippet(text, Snippet.Between("início", "Fim")),
        )
    }

    @Test
    fun naoEncontrado_null() {
        assertNull(extractSnippet("Só um parágrafo.", Snippet.NthBlock(2)))
        assertNull(extractSnippet("Só um parágrafo.", Snippet.Between("x", "y")))
    }
}
