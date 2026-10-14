package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #8c (GAP-2): comandos de recorte reconhecidos (e "insira isso" fora). */
class InsertSnippetParserTest {

    @Test
    fun insiraAOpcao2_nthBlock() {
        assertEquals(Snippet.NthBlock(2), parseInsertSnippet("insira a opção 2"))
    }

    @Test
    fun insiraA3_nthBlock() {
        assertEquals(Snippet.NthBlock(3), parseInsertSnippet("insira a 3"))
        assertEquals(Snippet.NthBlock(2), parseInsertSnippet("use a opção 2"))
    }

    @Test
    fun insiraOParagrafo1_nthParagraph() {
        assertEquals(Snippet.NthParagraph(1), parseInsertSnippet("insira o parágrafo 1"))
    }

    @Test
    fun insiraDeXAeY_between() {
        assertEquals(
            Snippet.Between("X", "Y"),
            parseInsertSnippet("insira de \"X\" a \"Y\""),
        )
    }

    @Test
    fun insiraIsso_null() {
        assertNull(parseInsertSnippet("insira isso"))
        assertNull(parseInsertSnippet("insira no tópico"))
        assertNull(parseInsertSnippet("insira só isso"))
    }
}
