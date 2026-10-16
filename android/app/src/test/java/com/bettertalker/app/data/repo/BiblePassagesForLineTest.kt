package com.bettertalker.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #16-fix-e — a linha do S-34 (com lista e múltiplos livros) expande nas
 * refs canônicas do índice nwt ("Sal 103:20"). Puro/testável.
 */
class BiblePassagesForLineTest {

    @Test
    fun linhaComListaEMultiplosLivrosExpande() {
        assertEquals(
            listOf("Sal 103:20", "Sal 103:21", "Da 7:10"),
            canonicalBibleRefs("(Sal 103:20, 21; Da 7:10)"),
        )
    }

    @Test
    fun linhaComUmaRefSo() {
        assertEquals(listOf("Sal 103:20"), canonicalBibleRefs("(Sal 103:20)"))
    }
}
