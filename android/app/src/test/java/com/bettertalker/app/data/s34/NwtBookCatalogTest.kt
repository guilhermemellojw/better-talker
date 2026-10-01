package com.bettertalker.app.data.s34

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwtBookCatalogTest {

    @Test
    fun catalog_has66Books() {
        assertEquals(66, NWT_BOOKS.size)
    }

    @Test
    fun catalog_usesTnmAbbreviations() {
        val abbrevs = NWT_BOOKS.values.map { it.abbrev }.toSet()
        for (expected in listOf("Pr", "He", "Tg", "Ap", "Flm", "Na", "Za", "Jz")) {
            assertTrue("falta abreviação $expected", expected in abbrevs)
        }
    }

    @Test
    fun catalog_docIdLookupWorks() {
        assertEquals("Êxodo", NWT_BOOKS["1001061106"]?.name)
        assertEquals("Êx", NWT_BOOKS["1001061106"]?.abbrev)
    }
}
