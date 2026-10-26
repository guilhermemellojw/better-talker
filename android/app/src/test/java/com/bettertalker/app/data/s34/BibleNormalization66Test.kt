package com.bettertalker.app.data.s34

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Fase 1 — normalização TNM para os 66 livros, por nome e por abreviação.
 * O inventário e o Copilot usam o mesmo resolvedor do checklist.
 */
class BibleNormalization66Test {

    @Test
    fun todosOs66LivrosNormalizamPeloNome() {
        assertEquals(66, NWT_BOOKS.size)
        for (book in NWT_BOOKS.values) {
            assertNotNull("nome não normalizou: ${book.name}", normalizeBibleBookName(book.name))
            assertEquals(
                "nome ${book.name}",
                book.abbrev,
                normalizeBibleBookName(book.name),
            )
        }
    }

    @Test
    fun todosOs66LivrosNormalizamPelaAbreviacao() {
        for (book in NWT_BOOKS.values) {
            assertEquals(
                "abreviação ${book.abbrev}",
                book.abbrev,
                normalizeBibleBookName(book.abbrev),
            )
        }
    }

    @Test
    fun abreviacoesSaoUnicas() {
        val abbrevs = NWT_BOOKS.values.map { it.abbrev }
        assertEquals(66, abbrevs.toSet().size)
    }
}
