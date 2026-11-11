package com.bettertalker.app.data.publication

import com.bettertalker.app.data.util.DocKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicationKindTest {

    @Test
    fun jwpubUsaTipoDoCatalogo() {
        assertEquals("book", publicationKindFor("be", DocKind.JWPUB))
        assertEquals("book", publicationKindFor("cl", DocKind.JWPUB))
        assertEquals("bible", publicationKindFor("nwt", DocKind.JWPUB))
    }

    @Test
    fun revistaDatadaCaiNoFallbackJwpub() {
        assertEquals("jwpub", publicationKindFor("wp17.09", DocKind.JWPUB))
        assertEquals("jwpub", publicationKindFor("w17.10", DocKind.JWPUB))
    }

    @Test
    fun formatosNaoJwpubPreservamFormato() {
        assertEquals("epub", publicationKindFor("be", DocKind.EPUB))
        assertEquals("pdf", publicationKindFor("cl", DocKind.PDF))
        assertEquals("docx", publicationKindFor("s-38", DocKind.DOCX))
    }
}
