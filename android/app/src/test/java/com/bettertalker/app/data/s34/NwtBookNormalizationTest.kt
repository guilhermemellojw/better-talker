package com.bettertalker.app.data.s34

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NwtBookNormalizationTest {

    @Test
    fun normalizeBibleBookName_genesis_returnsGen() {
        assertEquals("Gên", normalizeBibleBookName("Gênesis"))
    }

    @Test
    fun normalizeBibleBookName_withAccent_works() {
        assertEquals("Êx", normalizeBibleBookName("Êxodo"))
    }

    @Test
    fun normalizeBibleBookName_numberedBook_works() {
        assertEquals("1Sa", normalizeBibleBookName("1 Samuel"))
    }

    @Test
    fun normalizeBibleBookName_unknownName_returnsNull() {
        assertNull(normalizeBibleBookName("Livro Inventado"))
        assertNull(normalizeBibleBookName(""))
    }

    @Test
    fun normalizeBibleBookName_caseInsensitive_works() {
        assertEquals("Pr", normalizeBibleBookName("PROVÉRBIOS"))
        assertEquals("Ap", normalizeBibleBookName("apocalipse"))
    }

    @Test
    fun normalizeBibleBookName_extraSpaces_works() {
        assertEquals("Tg", normalizeBibleBookName("  Tiago  "))
        assertEquals("1Te", normalizeBibleBookName("1   Tessalonicenses"))
    }

    @Test
    fun normalizeBibleBookName_canticos_aliasWorks() {
        // RefDetector usa "Cânticos"; catálogo usa "Cântico de Salomão".
        assertEquals("Cân", normalizeBibleBookName("Cânticos"))
        assertEquals("Cân", normalizeBibleBookName("Cantares"))
    }

    @Test
    fun normalizeBibleBookName_filemom_aliasWorks() {
        // RefDetector usa "Filemom"; catálogo usa "Filêmon".
        assertEquals("Flm", normalizeBibleBookName("Filemom"))
    }
}
