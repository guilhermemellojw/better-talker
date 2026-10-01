package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.2.3a-fix4: símbolos de livros de estudo (página/range, mrt artigo,
 * ifi lição) + guarda do legado. Símbolos validados contra PubCatalog.
 */
class RefDetectorStudyBooksTest {

    private fun single(text: String): RefDetector.DetectedRef =
        RefDetector.detect(text).single()

    @Test
    fun detect_it1_detectsInsight() {
        val ref = single("Veja também (it-1 813).")
        assertEquals("it-1", ref.pubKey)
        assertEquals(RefDetector.Kind.BOOK, ref.kind)
        assertEquals("book|it-1", ref.editionKey)
        assertTrue(ref.label.contains("Estudo Perspicaz"))
    }

    @Test
    fun detect_it1_withRange_detectsPageRange() {
        val ref = single("(it-1 813-4)")
        assertEquals("it-1", ref.pubKey)
        assertTrue(ref.label.contains("pág. 813-4"))
    }

    @Test
    fun detect_it3_detectsIt3() {
        val ref = single("(it-3 575)")
        assertEquals("it-3", ref.pubKey)
        assertTrue(ref.label.contains("Volume 3"))
    }

    @Test
    fun detect_pe_detectsPoderaviver() {
        assertEquals("pe", single("(pe 175)").pubKey)
    }

    @Test
    fun detect_rs_detectsRaciocinios() {
        assertEquals("rs", single("(rs 328)").pubKey)
    }

    @Test
    fun detect_re_detectsRevelacao() {
        assertEquals("re", single("(re 292)").pubKey)
    }

    @Test
    fun detect_dp_detectsProfeciaDaniel() {
        assertEquals("dp", single("(dp 144)").pubKey)
    }

    @Test
    fun detect_dg_detectsDeveras() {
        val ref = single("(dg 27-8)")
        assertEquals("dg", ref.pubKey)
        assertTrue(ref.label.contains("pág. 27-8"))
    }

    @Test
    fun detect_bh_detectsBeneficieSe() {
        assertEquals("bh", single("(bh 109-110 §§ 10-11)").pubKey)
    }

    @Test
    fun detect_jv_detectsProclamadores() {
        assertEquals("jv", single("(jv 140)").pubKey)
    }

    @Test
    fun detect_kj_withRange_detectsPageRange() {
        val ref = single("(kj 264-5)")
        assertEquals("kj", ref.pubKey)
        assertTrue(ref.label.contains("pág. 264-5"))
    }

    @Test
    fun detect_mrtArtigo_detectsArticle() {
        val ref = single("mrt artigo 32")
        assertEquals("mrt", ref.pubKey)
        assertTrue(ref.label.contains("artigo 32"))
    }

    @Test
    fun detect_ifiLicao_detectsLesson() {
        val ref = single("ifi lição 45")
        // 3.2.3a-fix4b: "ifi" é alias de "ia" — canonicalizado no detect
        assertEquals("ia", ref.pubKey)
        assertEquals("book|ia", ref.editionKey)
        assertTrue(ref.label.contains("lição 45"))
    }

    @Test
    fun detect_ifiLicaoWithPoint_detectsBothNumbers() {
        val ref = single("ifi lição 24 ponto 3")
        assertEquals("ia", ref.pubKey)
        assertTrue(ref.label.contains("lição 24 ponto 3"))
    }

    // ---------- 3.2.3a-fix4b: canonicalização ----------

    @Test
    fun detect_ifiLicao_pubKeyIsIa() {
        val ref = single("(ifi lição 24 ponto 3)")
        assertEquals("ia", ref.pubKey)
        assertEquals("book|ia", ref.editionKey)
        assertTrue(ref.label.contains("Imite a Sua Fé (lição 24 ponto 3)"))
        // raw preserva a forma como veio no esboço (a rota ifi não captura
        // os parênteses externos — só o trecho "ifi lição …")
        assertEquals("ifi lição 24 ponto 3", ref.raw)
    }

    @Test
    fun detect_it3Page_pubKeyIsIt3() {
        // it-3 é símbolo válido — não muda com a canonicalização
        val ref = single("(it-3 575)")
        assertEquals("it-3", ref.pubKey)
        assertEquals("book|it-3", ref.editionKey)
        assertTrue(ref.label.contains("Estudo Perspicaz das Escrituras, Volume 3 (pág. 575)"))
    }

    @Test
    fun detect_iaBook_pubKeyIsIa() {
        assertEquals("ia", single("(ia 5)").pubKey)
        assertEquals("book|ia", single("(ia 5)").editionKey)
    }

    @Test
    fun detect_ifiAndIa_produceSameEditionKey() {
        val viaAlias = single("(ifi lição 24)")
        val viaCanonical = single("(ia lição 24)")
        assertEquals("book|ia", viaAlias.editionKey)
        assertEquals(viaAlias.editionKey, viaCanonical.editionKey)
    }

    // ---------- guarda do legado ----------

    @Test
    fun legacy_jyAvulsoStillWorks() {
        assertEquals("jy", single("(jy 15)").pubKey)
    }

    @Test
    fun legacy_w94_1_8_stillWorks() {
        val ref = single("(w94 1/8 3)")
        assertEquals("w", ref.pubKey)
        assertEquals("w|1994|8|1", ref.editionKey)
    }

    // ---------- 3.2.3a-fix4: chapterOf com pág./parág. ----------

    @Test
    fun chapterOf_paragAbrev_returnsParagrafo() {
        assertEquals(
            RefDetector.ChapterRef("paragrafo", 8),
            RefDetector.chapterOf("parág. 8-9")
        )
    }

    @Test
    fun chapterOf_pagAbrev_returnsPagina() {
        assertEquals(
            RefDetector.ChapterRef("pagina", 22),
            RefDetector.chapterOf("pág. 22 parág. 8-9")
        )
    }
}
