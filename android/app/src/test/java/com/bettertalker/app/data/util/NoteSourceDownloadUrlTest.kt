package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ação "Baixar no jw.org" precisa apontar para a PÁGINA da edição. */
class NoteSourceDownloadUrlTest {

    @Test
    fun sentinelaDeEstudo_abrePaginaDaEdicao() {
        val ref = RefDetector.detect("w25.07 22 §§ 8-9").single()
        val (url, hint, exact) = RefDetector.downloadUrl(ref)
        assertEquals(
            "https://www.jw.org/pt/biblioteca/revistas/sentinela-estudo-julho-2025/",
            url,
        )
        assertTrue(exact)
        assertTrue(hint.contains("Abre a edição"))
    }

    @Test
    fun lmd_abrePaginaDaPublicacaoNoFinder() {
        val ref = RefDetector.detect("lmd lição 7 ponto 4").single()
        val (url, _, exact) = RefDetector.downloadUrl(ref)
        assertEquals(
            "https://www.jw.org/finder?wtlocale=T&pub=lmd&srcid=share",
            url,
        )
        assertTrue(exact)
    }

    @Test
    fun despertaiMensal_abrePaginaDaEdicao() {
        val ref = RefDetector.detect("g 1/24").single()
        val (url, _, exact) = RefDetector.downloadUrl(ref)
        assertEquals("https://www.jw.org/pt/biblioteca/revistas/g202401/", url)
        assertTrue(exact)
    }

    @Test
    fun sentinelaAntigaDia1_usaPrefixoPublico() {
        val ref = RefDetector.detect("w09 1/8").single()
        val (url, _, exact) = RefDetector.downloadUrl(ref)
        assertEquals("https://www.jw.org/pt/biblioteca/revistas/wp20090801/", url)
        assertTrue(exact)
    }

    @Test
    fun sentinelaAntigaDia15_usaPrefixoEstudo() {
        val ref = RefDetector.detect("w09 15/8").single()
        val (url, _, exact) = RefDetector.downloadUrl(ref)
        assertEquals("https://www.jw.org/pt/biblioteca/revistas/w20090815/", url)
        assertTrue(exact)
    }

    @Test
    fun tnm_abreLandingOficial() {
        val ref = RefDetector.DetectedRef(
            raw = "TNM",
            kind = RefDetector.Kind.BOOK,
            pubKey = "nwt",
            editionKey = "book|nwt",
            label = "TNM",
        )
        val (url, _, exact) = RefDetector.downloadUrl(ref)
        assertTrue(url.contains("/biblioteca/biblia/nwt/"))
        assertTrue(exact)
    }
}
