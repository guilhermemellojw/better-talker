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

    @Test
    fun orderForPublicacaoS34ENwt() {
        assertEquals(listOf("JWPUB", "EPUB", "PDF"), PublicationFormatPolicy.orderFor("w"))
        assertEquals(listOf("JWPUB", "DOCX", "PDF"), PublicationFormatPolicy.orderFor("s34"))
        assertEquals(listOf("EPUB"), PublicationFormatPolicy.orderFor("nwt"))
        assertEquals(
            listOf("JWPUB", "EPUB", "PDF"),
            PublicationFormatPolicy.orderFor("s38"),
        )
    }

    @Test
    fun reparoPreservaBaseSlotDeBeThES38() {
        assertEquals(
            "be",
            PublicationFormatPolicy.repairedBaseSlot("be", "be", null, "be"),
        )
        assertEquals(
            "th",
            PublicationFormatPolicy.repairedBaseSlot("th", "th", null, "th"),
        )
        assertEquals(
            "s38",
            PublicationFormatPolicy.repairedBaseSlot("s38", "s38", null, "s38"),
        )
        assertEquals(
            "nwt",
            PublicationFormatPolicy.repairedBaseSlot("nwt", "nwt", null, "nwt"),
        )
        assertEquals(
            "be",
            PublicationFormatPolicy.repairedBaseSlot("be", "be", null, null),
        )
    }

    @Test
    fun repairDaTnmSoAceitaEpubTnm() {
        assertNull(
            PublicationFormatPolicy.nwtRepairBlockedReason("nwt", "epub", "nwt")
        )
        assertTrue(
            PublicationFormatPolicy.nwtRepairBlockedReason("nwt", "pdf", "nwt")!!.contains("EPUB")
        )
        assertTrue(
            PublicationFormatPolicy.nwtRepairBlockedReason("nwt", "epub", null)!!.contains("EPUB")
        )
        assertNull(
            PublicationFormatPolicy.nwtRepairBlockedReason("be", "pdf", null)
        )
    }

    @Test
    fun s34PorNomeEIdentificado() {
        assertTrue(PublicationFormatPolicy.isS34File("S-34_T_035.docx"))
        assertTrue(PublicationFormatPolicy.isS34File("s34.pdf"))
        assertFalse(PublicationFormatPolicy.isS34File("w25.07.pdf"))
    }

    @Test
    fun bloqueioDeDownloadNwtAntesDeEnfileirar() {
        assertTrue(
            PublicationFormatPolicy.downloadBlockedReason("nwt_T.pdf")!!.contains("EPUB")
        )
        assertTrue(
            PublicationFormatPolicy.downloadBlockedReason("nwt_T.jwpub")!!.contains("EPUB")
        )
        assertNull(PublicationFormatPolicy.downloadBlockedReason("nwt_T.epub"))
        assertNull(PublicationFormatPolicy.downloadBlockedReason("w25.07.pdf"))
    }

    @Test
    fun downloadNwtUsaUrlQuandoNomeVemGenerico() {
        assertNull(
            PublicationFormatPolicy.downloadBlockedReason(
                "nwt_T.epub",
                sourceUrl = "https://cfp2.jw-cdn.org/a/40194f/3/o/nwt_T.epub",
            )
        )
        assertNull(
            PublicationFormatPolicy.downloadBlockedReason(
                "download.bin",
                sourceUrl = "https://cfp2.jw-cdn.org/a/40194f/3/o/nwt_T.epub?x=1",
            )
        )
        assertTrue(
            PublicationFormatPolicy.downloadBlockedReason(
                "download.bin",
                sourceUrl = "https://cfp2.jw-cdn.org/a/40194f/3/o/nwt_T.pdf",
            )!!.contains("EPUB")
        )
        assertTrue(
            PublicationFormatPolicy.downloadBlockedReason(
                "nwt_T.pdf",
                sourceUrl = "https://cfp2.jw-cdn.org/a/40194f/3/o/nwt_T.pdf",
            )!!.contains("EPUB")
        )
    }
}
