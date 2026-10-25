package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fase 2 — extração não pode inserir espaço dentro de palavra partida por tags inline. */
class StripXmlTest {

    @Test
    fun stripXml_removeTagsInlineSemInserirEspacoNaPalavra() {
        assertEquals(
            "Jeová Deus",
            stripXml("<p><span>Jeov</span><span>á</span> Deus</p>"),
        )
    }

    @Test
    fun stripXml_preservaFronteiraDeParagrafo() {
        assertEquals(
            "Primeiro.\n\nSegundo.",
            stripXml("<p>Primeiro.</p><p>Segundo.</p>"),
        )
    }

    @Test
    fun stripXml_tagInlineComEspacoExistente() {
        assertEquals(
            "Negrito normal",
            stripXml("<p><b>Negrito</b> normal</p>"),
        )
    }

    @Test
    fun stripXml_brViraQuebraDeLinha() {
        assertEquals("a\nb", stripXml("a<br/>b"))
    }
}
