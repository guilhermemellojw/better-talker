package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C2.6 — cauda de página/§ nas revistas nomeadas. */
class MagazineTailTest {

    @Test
    fun despertaPageOnly() {
        val ref = RefDetector.detect("Despertai! 08/13 pág. 6")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("g|2013|8", ref.editionKey)
        assertEquals(6, ref.page)
        assertNull(ref.paragraph)
        assertNull(ref.pageEnd)
    }

    @Test
    fun despertaPageAndParagraphRange() {
        val ref = RefDetector.detect("Despertai! 08/13 pág. 8 parág. 1-2")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("g|2013|8", ref.editionKey)
        assertEquals(8, ref.page)
        assertEquals(1, ref.paragraph)
        assertEquals(2, ref.paragraphEnd)
    }

    @Test
    fun sentinelaNamedPageRange() {
        val ref = RefDetector.detect("Sentinela número 3 de 2019 pág. 6-7")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("wp|2019|3", ref.editionKey)
        assertEquals(6, ref.page)
        assertEquals(7, ref.pageEnd)
        assertNull(ref.paragraph)
    }

    @Test
    fun sentinelaMonthParagraph() {
        val ref = RefDetector.detect("Sentinela de julho de 2025 pág. 22 § 8-9")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("w|2025|7", ref.editionKey)
        assertEquals(22, ref.page)
        assertEquals(8, ref.paragraph)
        assertEquals(9, ref.paragraphEnd)
    }

    @Test
    fun sentinelaOldDateTailStillWorks() {
        val ref = RefDetector.detect("Sentinela 01/20 pág. 22 parág. 8-9")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("w|2020|1", ref.editionKey)
        assertEquals(22, ref.page)
        assertEquals(8, ref.paragraph)
        assertEquals(9, ref.paragraphEnd)
    }

    @Test
    fun nbspEZerowidthNaoPerdemUnidade() {
        val despertai = RefDetector.detect("Despertai! 10/10 pág. 3")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals(3, despertai.page)
        val sentinela = RefDetector.detect("Sentinela 15/04/10 pág. 9 parág. 11")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals(9, sentinela.page)
        assertEquals(11, sentinela.paragraph)
        val range = RefDetector.detect("w15 15/4 22-​23 §§ 14")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals(22, range.page)
        assertEquals(23, range.pageEnd)
        assertEquals(14, range.paragraph)
    }

    @Test
    fun pilcrowViraParagrafo() {
        val ref = RefDetector.detect("w98 15/2 26 § ¶5")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals(26, ref.page)
        assertEquals(5, ref.paragraph)
    }

    @Test
    fun lvsComPaginaEParagraph() {
        val ref = RefDetector.detect("lvs 8-9 §§ 8-9")
            .single { it.editionKey == "book|lvs" }
        assertEquals(8, ref.page)
        assertEquals(9, ref.pageEnd)
        assertEquals(8, ref.paragraph)
        assertEquals(9, ref.paragraphEnd)
    }
}
