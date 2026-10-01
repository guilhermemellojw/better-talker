package com.bettertalker.app.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PublicationSymbolCanonicalizationTest {

    @Test
    fun canonical_w_2021_08_returnsW2108() {
        assertEquals("w21.08", canonicalPublicationSymbol("w", "w|2021|8"))
    }

    @Test
    fun canonical_w_1999_05_returnsW9905() {
        assertEquals("w99.05", canonicalPublicationSymbol("w", "w|1999|5"))
    }

    @Test
    fun canonical_w_2019_03_returnsW1903() {
        assertEquals("w19.03", canonicalPublicationSymbol("w", "w|2019|3"))
    }

    @Test
    fun canonical_wp_2019_03_returnsWp1903() {
        assertEquals("wp19.03", canonicalPublicationSymbol("wp", "wp|2019|3"))
    }

    @Test
    fun canonical_g_2013_08_returnsG8_13() {
        assertEquals("g 8/13", canonicalPublicationSymbol("g", "g|2013|8"))
    }

    @Test
    fun canonical_g_2007_06_returnsG6_07() {
        assertEquals("g 6/07", canonicalPublicationSymbol("g", "g|2007|6"))
    }

    @Test
    fun canonical_gn_2024_01_returnsGn1_24() {
        assertEquals("gn 1/24", canonicalPublicationSymbol("gn", "gn|2024|1"))
    }

    @Test
    fun canonical_book_ignoresEdition() {
        assertEquals("be", canonicalPublicationSymbol("be", "book|be"))
        assertEquals("th", canonicalPublicationSymbol("th", "book|th"))
    }

    @Test
    fun canonical_emptyEdition_returnsPubKey() {
        assertEquals("w", canonicalPublicationSymbol("w", ""))
    }

    @Test
    fun canonical_malformedEdition_returnsPubKey() {
        assertEquals("w", canonicalPublicationSymbol("w", "w"))
        assertEquals("w", canonicalPublicationSymbol("w", "w||3"))
        assertEquals("w", canonicalPublicationSymbol("w", "w|20x9|3"))
    }

    @Test
    fun canonical_unknownPubKey_returnsPubKey() {
        assertEquals("xyz", canonicalPublicationSymbol("xyz", "xyz|2024|1"))
    }

    @Test
    fun canonical_wVsWp_preservesDistinction() {
        val study = canonicalPublicationSymbol("w", "w|2019|3")
        val public = canonicalPublicationSymbol("wp", "wp|2019|3")
        assertEquals("w19.03", study)
        assertEquals("wp19.03", public)
        assertNotEquals(study, public)
    }

    // ---------- 3.2.3a-fix3: formato quinzenal antigo ----------

    @Test
    fun canonical_w_1994_08_1_returnsW94_1_8() {
        assertEquals("w94 1/8", canonicalPublicationSymbol("w", "w|1994|8|1"))
    }

    @Test
    fun canonical_w_1994_08_15_returnsW94_15_8() {
        assertEquals("w94 15/8", canonicalPublicationSymbol("w", "w|1994|8|15"))
    }

    @Test
    fun canonical_g_1993_01_8_returnsG93_8_1() {
        assertEquals("g93 8/1", canonicalPublicationSymbol("g", "g|1993|1|8"))
    }

    @Test
    fun canonical_modernFormat_stillWorks() {
        assertEquals("w21.08", canonicalPublicationSymbol("w", "w|2021|8"))
    }

    @Test
    fun canonical_modernAwake_stillWorks() {
        assertEquals("g 6/07", canonicalPublicationSymbol("g", "g|2007|6"))
    }

    @Test
    fun canonical_pubVsStudy_distinctSymbols() {
        val pub = canonicalPublicationSymbol("w", "w|1994|8|1")
        val study = canonicalPublicationSymbol("w", "w|1994|8|15")
        assertEquals("w94 1/8", pub)
        assertEquals("w94 15/8", study)
        assertNotEquals(pub, study)
    }
}
