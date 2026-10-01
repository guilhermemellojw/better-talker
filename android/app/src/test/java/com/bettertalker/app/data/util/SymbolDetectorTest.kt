package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolDetectorTest {

    @Test
    fun detectSymbol_beFileName_returnsBe() {
        assertEquals("be", detectSymbol("be_t_escola.epub"))
    }

    @Test
    fun detectSymbol_thFileName_returnsTh() {
        assertEquals("th", detectSymbol("th_t_leitura.epub"))
    }

    @Test
    fun detectSymbol_w1903FileName_returnsFallback() {
        // "w19.03": prefixo "w" não é seguido de separador (vem "1"), então
        // o regex não casa e cai no fallback (nome base, <= 24 chars).
        assertEquals("w19.03", detectSymbol("w19.03.pdf"))
    }

    @Test
    fun detectSymbol_unknownLongName_returns24CharsMax() {
        // Prefixo com 5+ letras antes do separador não casa ([a-z]{1,4}) → fallback.
        val base = "abcdefghi_resto_do_nome_bem_longo"
        val symbol = detectSymbol("$base.pdf")
        assertEquals(base.take(24), symbol)
        assertTrue(symbol.length <= 24)
    }

    // ---------- 3.2.3a-fix2: revistas com edição ----------

    @Test
    fun detectSymbol_w2019_03_returnsW2019_03() {
        assertEquals("w2019.03", detectSymbol("w2019.03.pdf"))
    }

    @Test
    fun detectSymbol_wp1903_returnsWp1903() {
        assertEquals("wp19.03", detectSymbol("wp19.03.pdf"))
    }

    @Test
    fun detectSymbol_gSpace_returnsG6_07() {
        assertEquals("g 6/07", detectSymbol("g 6/07.pdf"))
    }

    @Test
    fun detectSymbol_gNoSpace_returnsG6_07() {
        assertEquals("g 6/07", detectSymbol("g6/07.pdf"))
    }

    @Test
    fun detectSymbol_gn_returnsGn1_24() {
        assertEquals("gn 1/24", detectSymbol("gn 1/24.pdf"))
        assertEquals("gn 1/24", detectSymbol("gn1/24.pdf"))
    }

    @Test
    fun detectSymbol_wCompactJwOrg_returnsW2103() {
        assertEquals("w21.03", detectSymbol("w_T_202103.pdf"))
    }

    @Test
    fun detectSymbol_wpCompactJwOrg_returnsWp1909() {
        assertEquals("wp19.09", detectSymbol("wp_T_201909.pdf"))
    }

    @Test
    fun detectSymbol_nwt_returnsNwt() {
        assertEquals("nwt", detectSymbol("nwt_T.epub"))
    }

    @Test
    fun detectSymbol_th_returnsTh() {
        assertEquals("th", detectSymbol("th_t_leitura.epub"))
    }

    @Test
    fun detectSymbol_invalidMonth_fallsBackToTake24() {
        // Mês 19 é inválido (rejeitado de propósito); não vira "g 19/03" —
        // cai no fallback take(24), preservando o nome base.
        assertEquals("g19.03", detectSymbol("g19.03.pdf"))
    }

    // ---------- 3.2.3a-fix3: formato quinzenal antigo ----------

    @Test
    fun detectSymbol_w94_1_8_pdf_returnsW94_1_8() {
        assertEquals("w94 1/8", detectSymbol("w94 1/8.pdf"))
    }

    @Test
    fun detectSymbol_g93_8_1_pdf_returnsG93_8_1() {
        assertEquals("g93 8/1", detectSymbol("g93 8/1.pdf"))
    }

    @Test
    fun detectSymbol_oldDotSeparator_normalizesToSlash() {
        assertEquals("w82 15/3", detectSymbol("w82 15.3.pdf"))
    }

    @Test
    fun detectSymbol_modernStillWorks() {
        assertEquals("w19.03", detectSymbol("w19.03.pdf"))
    }

    @Test
    fun detectSymbol_modernAwakeStillWorks() {
        assertEquals("g 6/07", detectSymbol("g 6/07.pdf"))
    }

    @Test
    fun buildRef_withSection_formatsCorrectly() {
        assertEquals("nwt João 5 §3", buildRef("nwt", "João 5", 3))
    }

    @Test
    fun buildRef_withoutSection_omitsSection() {
        assertEquals("nwt §3", buildRef("nwt", "", 3))
    }

    @Test
    fun buildRef_longSection_truncatesTo60() {
        val section = "A".repeat(100)
        val ref = buildRef("nwt", section, 1)
        assertEquals("nwt ${"A".repeat(60)} §1", ref)
    }
}
