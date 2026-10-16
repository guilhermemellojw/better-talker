package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #16-fix-f — canonicalização de refs (livros, ranges, listas, páginas). Puro. */
class RefCanonicalizerTest {

    @Test
    fun livroCompletoViraAbreviacao() {
        assertTrue(RefCanonicalizer.canonicalBook("1 Coríntios 14:33").contains("1co1433"))
    }

    @Test
    fun rangeExpandeNosVersiculos() {
        assertEquals(
            listOf("Jó 38:31", "Jó 38:32", "Jó 38:33"),
            RefCanonicalizer.expandRanges("Jó 38:31-33"),
        )
    }

    @Test
    fun paginasComAExpandem() {
        assertEquals(
            listOf("26", "27", "28"),
            RefCanonicalizer.extractPageNumbers("páginas 26 a 28"),
        )
    }

    @Test
    fun rangeNumericoSoltoExpande() {
        assertEquals(listOf("26", "27", "28"), RefCanonicalizer.extractPageNumbers("26-28"))
    }

    @Test
    fun salmoComListaCanonicaliza() {
        val forms = RefCanonicalizer.canonicalize("Salmo 103:20, 21").joinToString(" ")
        assertTrue(forms.contains("sal10320"))
        assertTrue(forms.contains("sal10321"))
    }
}
