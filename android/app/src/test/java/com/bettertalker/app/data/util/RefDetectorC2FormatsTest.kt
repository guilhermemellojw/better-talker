package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2.2 — formatos de ref que faltavam no detector:
 * `bt`/`kr` com faixa de página, revista antiga com página/§, `mrt artigo N`,
 * `lff 27` e faixa de página moderna.
 */
class RefDetectorC2FormatsTest {

    @Test
    fun btComFaixaDePaginaEParagraphs() {
        val ref = RefDetector.detect("(1Ti 2:3; bt 57- 59 §§ 14- 18)")
            .single { it.pubKey == "bt" }
        assertEquals("book|bt", ref.editionKey)
        assertEquals(57, ref.page)
        assertEquals(59, ref.pageEnd)
        assertEquals(14, ref.paragraph)
        assertEquals(18, ref.paragraphEnd)
        assertTrue(ref.label.contains("pág. 57-59"))
        assertTrue(ref.label.contains("§§ 14-18"))
    }

    @Test
    fun krComFaixaDePaginaEParagraphs() {
        val ref = RefDetector.detect("(kr 120- 125 §§ 11- 18)")
            .single { it.pubKey == "kr" }
        assertEquals(120, ref.page)
        assertEquals(125, ref.pageEnd)
        assertEquals(11, ref.paragraph)
        assertEquals(18, ref.paragraphEnd)
    }

    @Test
    fun krSoComFaixaDePagina() {
        val ref = RefDetector.detect("(kr 96- 97; dados do último Anuário)")
            .single { it.pubKey == "kr" }
        assertEquals(96, ref.page)
        assertEquals(97, ref.pageEnd)
        assertNull(ref.paragraph)
    }

    @Test
    fun revistaAntigaComFaixaDePagina() {
        val ref = RefDetector.detect("(Jó 38:31-33; w11 1/7 26- 28)")
            .single { it.editionKey == "w|2011|7|1" }
        assertEquals(26, ref.page)
        assertEquals(28, ref.pageEnd)
        assertNull(ref.paragraph)
    }

    @Test
    fun revistaAntigaComPaginaEParagraph() {
        val ref = RefDetector.detect("(w94 1/8 10 § 5)")
            .single { it.editionKey == "w|1994|8|1" }
        assertEquals(10, ref.page)
        assertNull(ref.pageEnd)
        assertEquals(5, ref.paragraph)
    }

    @Test
    fun revistaModernaComFaixaDePagina() {
        val ref = RefDetector.detect("(w25.07 22-24 §§ 8-9)")
            .single { it.editionKey == "w|2025|7" }
        assertEquals(22, ref.page)
        assertEquals(24, ref.pageEnd)
        assertEquals(8, ref.paragraph)
        assertEquals(9, ref.paragraphEnd)
    }

    @Test
    fun mrtArtigoPreservaUnidade() {
        val ref = RefDetector.detect("(mrt artigo 32)").single { it.pubKey == "mrt" }
        assertEquals("artigo 32", ref.article)
        assertEquals("artigo 32", ref.chapter)
    }

    @Test
    fun lffNumeroAvulsoViraEstudo() {
        val ref = RefDetector.detect("ver lff 27 sobre ele").single { it.pubKey == "lff" }
        assertEquals("estudo 27", ref.chapter)
        assertTrue(ref.label.contains("estudo 27"))
    }

    @Test
    fun lmdLiteralEhDetectadoSemUnidade() {
        val ref = RefDetector.detect("veja lmd no texto").single { it.pubKey == "lmd" }
        assertEquals("book|lmd", ref.editionKey)
        assertEquals(null, ref.chapter)
        assertEquals(null, ref.paragraph)
    }

    @Test
    fun lmdLiteralNaoDuplicaUnidadeEspecifica() {
        val refs = NoteRefsCollector.collect(plainText = "lmd; lmd lição 7 ponto 4")
        val lmd = refs.publications.filter { it.pubKey == "lmd" }
        assertEquals(1, lmd.size)
        assertEquals("lição 7", lmd.single().chapter)
        assertEquals(4, lmd.single().paragraph)
    }

    @Test
    fun paragraphNoInicioDoTextoContaComoUnidade() {
        val text = "10 O que Jeová fez para nos libertar"
        val passage = com.bettertalker.app.data.db.PassageEntity(
            id = "p1",
            attachmentId = "bhs",
            text = text,
            normalized = text.lowercase(),
            section = "Capítulo 5",
            ref = "bhs Capítulo 5 §1985",
        )
        val ref = RefDetector.DetectedRef(
            raw = "bhs cap 5 §10",
            kind = RefDetector.Kind.BOOK,
            pubKey = "bhs",
            editionKey = "book|bhs",
            label = "Entenda a Bíblia",
            chapter = "cap 5",
            paragraph = 10,
        )

        assertEquals(10, RefDetector.leadingParagraphNumber(text))
        assertNull(RefDetector.leadingParagraphNumber("1 João 4:8"))
        assertNull(RefDetector.leadingParagraphNumber("1 Coríntios 14:33"))
        assertNull(
            RefDetector.leadingParagraphNumber(
                "10 pessoas estavam na sala e conversavam sobre a vida.",
            ),
        )
        val check = RefDetector.resolveContent(ref, listOf(passage), corpusAvailable = true)
        assertTrue(check.resolved)
        assertTrue(check.snippet!!.contains("O que Jeová fez"))
    }

    @Test
    fun s34RealAgoraDetectaBtEKrs() {
        val raw = javaClass.getResourceAsStream("/fixtures/S-34_T_003.txt")!!
            .bufferedReader().readText()
        val refs = NoteRefsCollector.collect(plainText = raw)

        assertEquals(11, refs.publications.size)
        assertEquals(59, refs.bibles.size)
        assertEquals(70, refs.publications.size + refs.bibles.size)
        assertTrue(refs.publications.any { it.pubKey == "bt" && it.page == 57 && it.pageEnd == 59 })
        assertEquals(
            2,
            refs.publications.count { it.pubKey == "kr" },
        )
    }
}
