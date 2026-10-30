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
}
