package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationFormatPolicyTest {

    @Test
    fun publicacoesPreferemJwpubDepoisEpubDepoisPdf() {
        assertEquals(listOf("JWPUB", "EPUB", "PDF"), PublicationFormatPolicy.PUBLICATION_ORDER)
    }

    @Test
    fun s34PrefereJwpubDocxEPdfSoFallback() {
        assertEquals(listOf("JWPUB", "DOCX", "PDF"), PublicationFormatPolicy.S34_ORDER)
        assertNull(PublicationFormatPolicy.s34Warning("docx"))
        assertNull(PublicationFormatPolicy.s34Warning("jwpub"))
        assertTrue(PublicationFormatPolicy.s34Warning("pdf")!!.contains("fallback"))
    }

    @Test
    fun pdfDePublicacaoTemAvisoDeFormatoLimitado() {
        assertTrue(PublicationFormatPolicy.publicationWarning("pdf")!!.contains("PDF limitado"))
        assertNull(PublicationFormatPolicy.publicationWarning("epub"))
        assertNull(PublicationFormatPolicy.publicationWarning("jwpub"))
    }

    @Test
    fun nwtSoAceitaEpubEVersaoIndexada() {
        assertTrue(PublicationFormatPolicy.isNwtBlocked("pdf"))
        assertTrue(PublicationFormatPolicy.isNwtBlocked("jwpub"))
        assertFalse(PublicationFormatPolicy.isNwtBlocked("epub"))
        assertTrue(PublicationFormatPolicy.nwtBlockedMessage("pdf").contains("EPUB"))
        assertTrue(PublicationFormatPolicy.isNwtVerseReady("nwt", "epub", true))
        assertFalse(PublicationFormatPolicy.isNwtVerseReady("nwt", "pdf", true))
        assertFalse(PublicationFormatPolicy.isNwtVerseReady("nwt", "jwpub", true))
        assertFalse(PublicationFormatPolicy.isNwtVerseReady("nwt", "epub", false))
        assertFalse(PublicationFormatPolicy.isNwtVerseReady("be", "epub", true))
    }

    @Test
    fun nwtOrderSoEpub() {
        assertEquals(listOf("EPUB"), PublicationFormatPolicy.NWT_ORDER)
    }
}
