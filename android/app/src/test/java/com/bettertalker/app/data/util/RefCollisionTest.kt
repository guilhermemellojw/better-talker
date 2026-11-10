package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.3.1 — colisão entre referência bíblica e publicação:
 * - "Jeremias 52" é Bíblia (Je 52), nunca o livro jr;
 * - "jr 29"/"jr p. 29"/"jr cap. 1" continuam publicação;
 * - "Re 1:1" é Apocalipse, nunca a publicação re;
 * - símbolos bíblicos com unidade explícita continuam publicação.
 */
class RefCollisionTest {

    private fun pubs(text: String) = RefDetector.detect(text)

    @Test
    fun jeremiasCapituloEhBibliaSemJr() {
        assertTrue(pubs("Jeremias 52").isEmpty())
        val chapters = RefDetector.detectBibleChapters("Jeremias 52")
        assertEquals(listOf("Jeremias" to 52), chapters.map { it.label to it.chapter })
    }

    @Test
    fun jeremiasVersiculoEhBibliaSemJr() {
        assertTrue(pubs("Jeremias 52:1").isEmpty())
        val verses = RefDetector.detectBible("Jeremias 52:1")
        assertEquals(listOf("Jeremias" to 52 to 1), verses.map { it.label to it.chapter to it.verse })
    }

    @Test
    fun jrBarePageChapterContinuamPublicacao() {
        val bare = pubs("jr 29").single()
        assertEquals("jr", bare.pubKey)
        assertEquals("book|jr", bare.editionKey)
        assertTrue(bare.label.contains("Jeremias — Uma Mensagem de Deus para Nós"))
        val page = pubs("jr p. 29").single()
        assertEquals("jr", page.pubKey)
        assertTrue(page.label.contains("Jeremias — Uma Mensagem de Deus para Nós — p. 29"))
        assertEquals("jr", pubs("jr cap. 1").single().pubKey)
    }

    @Test
    fun jeremiasCapituloEJrNaMesmaFraseFicamSeparados() {
        val publications = pubs("Jeremias 52; jr 29")
        assertEquals(listOf("jr"), publications.map { it.pubKey })
        assertEquals(
            listOf("Jeremias" to 52),
            RefDetector.detectBibleChapters("Jeremias 52; jr 29")
                .map { it.label to it.chapter },
        )
    }

    @Test
    fun reVersiculoEhBibliaSemPublicacao() {
        assertTrue(pubs("Re 1:1").isEmpty())
        assertTrue(pubs("(Re 1:1)").isEmpty())
        assertTrue(pubs("Re 1").isEmpty())
        assertTrue(pubs("re 1").isEmpty())
        val verses = RefDetector.detectBible("Re 1:1")
        assertEquals(listOf("Apocalipse" to 1 to 1), verses.map { it.label to it.chapter to it.verse })
    }

    @Test
    fun jeCapituloEhBibliaSemPublicacaoJe() {
        assertTrue(pubs("Je 29").isEmpty())
        assertTrue(pubs("(Je 29)").isEmpty())
        assertTrue(pubs("Je 29:11").isEmpty())
    }

    @Test
    fun reComUnidadeExplicitaContinuaPublicacao() {
        assertEquals("re", pubs("re p. 10").single().pubKey)
        assertEquals("re", pubs("re cap. 1").single().pubKey)
    }

    @Test
    fun isaiasVersiculoEIp1CapituloFicamSeparados() {
        val publications = pubs("Isaías 1:1; ip-1 cap. 1")
        assertEquals(listOf("ip-1"), publications.map { it.pubKey })
        assertEquals(
            listOf("Isaías" to 1 to 1),
            RefDetector.detectBible("Isaías 1:1; ip-1 cap. 1")
                .map { it.label to it.chapter to it.verse },
        )
    }

    @Test
    fun danielVersiculoEDpPaginaFicamSeparados() {
        val publications = pubs("Daniel 2:1; dp p. 10")
        assertEquals(listOf("dp"), publications.map { it.pubKey })
        assertEquals(
            listOf("Daniel" to 2 to 1),
            RefDetector.detectBible("Daniel 2:1; dp p. 10")
                .map { it.label to it.chapter to it.verse },
        )
    }
}
