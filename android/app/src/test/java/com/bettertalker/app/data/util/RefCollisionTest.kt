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
        // Bare "Re 1" é alias não oficial: prefere a publicação.
        assertEquals("re", pubs("Re 1").single().pubKey)
        assertEquals("re", pubs("re 1").single().pubKey)
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
    fun reForaDoTotalDeCapitulosViraPaginaDaPublicacao() {
        assertEquals("re", pubs("Re 23").single().pubKey)
        assertEquals(23, pubs("Re 23").single().page)
        assertEquals("re", pubs("Re 89").single().pubKey)
        assertEquals(89, pubs("Re 89").single().page)
        val range = pubs("Re 89-99").single()
        assertEquals("re", range.pubKey)
        assertEquals(89, range.page)
        assertEquals(99, range.pageEnd)
    }

    @Test
    fun reCapituloSozinhoPreferePublicacaoMasAp22EhBiblia() {
        // Alias não oficial (Re): capítulo solto prefere a publicação.
        assertEquals("re", pubs("Re 22").single().pubKey)
        assertEquals(22, pubs("Re 22").single().page)
        assertTrue(pubs("Ap 22").isEmpty())
        assertEquals(
            listOf("Apocalipse" to 22),
            RefDetector.detectBibleChapters("Ap 22").map { it.label to it.chapter },
        )
        // v5.3.1: capítulo válido, versículo inexistente continua Bíblia;
        // o reader resolve como UNRESOLVED.
        assertTrue(pubs("Re 22:21").isEmpty())
        assertEquals(
            listOf("Apocalipse" to 22 to 21),
            RefDetector.detectBible("Re 22:21").map { it.label to it.chapter to it.verse },
        )
    }

    @Test
    fun colisõesOficiaisBareSaoBibliaENaoPublicacao() {
        for ((text, book) in listOf(
            "Je 29" to "Jeremias",
            "Jl 1" to "Joel",
            "La 1" to "Lamentações",
            "Na 1" to "Naum",
            "Pr 1" to "Provérbios",
        )) {
            assertTrue("publication leak: $text", pubs(text).isEmpty())
            assertEquals(
                listOf(book),
                RefDetector.detectBibleChapters(text).map { it.label },
            )
        }
        // Publicação oficial exige unidade explícita.
        assertEquals("je", pubs("je p. 5").single().pubKey)
        assertEquals("jl", pubs("jl p. 5").single().pubKey)
        assertEquals("la", pubs("la p. 5").single().pubKey)
        assertEquals("na", pubs("na p. 5").single().pubKey)
        assertEquals("pr", pubs("pr p. 5").single().pubKey)
    }

    @Test
    fun aliasesNaoOficiaisSoSaoBibliaComCapVers() {
        for ((text, pub) in listOf(
            "Ed 10" to "ed",
            "Gl 5" to "gl",
            "Hb 13" to "hb",
            "Jd 1" to "jd",
            "Lc 5" to "lc",
            "Ml 4" to "ml",
            "Cl 5" to "cl",
            "Dn 13" to "dn",
        )) {
            assertEquals("publication missing: $text", pub, pubs(text).single().pubKey)
        }
        for ((text, book) in listOf(
            "Ed 1:1" to "Esdras",
            "Gl 1:1" to "Gálatas",
            "Hb 1:1" to "Hebreus",
            "Jd 1:1" to "Judas",
            "Lc 1:1" to "Lucas",
            "Ml 1:1" to "Malaquias",
            "Cl 1:1" to "Colossenses",
            "Dn 1:1" to "Daniel",
        )) {
            assertEquals(
                "Bible missing: $text",
                listOf(book to 1 to 1),
                RefDetector.detectBible(text).map { it.label to it.chapter to it.verse },
            )
        }
    }

    @Test
    fun clEDnSaoPublicacoesENaoAliasesDeBiblia() {
        // cl = Achegue-se a Jeová; dn = Regência Divina. Col/Da são Bíblia.
        assertEquals("cl", pubs("Cl 5").single().pubKey)
        assertEquals(5, pubs("Cl 5").single().page)
        assertEquals("cl", pubs("Cl 4").single().pubKey)
        assertEquals(4, pubs("Cl 4").single().page)
        assertEquals("dn", pubs("Dn 13").single().pubKey)
        assertEquals(13, pubs("Dn 13").single().page)
        assertEquals("dn", pubs("Dn 12").single().pubKey)
        assertEquals(12, pubs("Dn 12").single().page)
        assertTrue(pubs("Col 4").isEmpty())
        assertTrue(pubs("Da 12").isEmpty())
        assertEquals(
            listOf("Colossenses" to 4),
            RefDetector.detectBibleChapters("Col 4").map { it.label to it.chapter },
        )
        assertEquals(
            listOf("Daniel" to 12),
            RefDetector.detectBibleChapters("Da 12").map { it.label to it.chapter },
        )
        assertEquals(
            listOf("Colossenses" to 1 to 1),
            RefDetector.detectBible("Col 1:1").map { it.label to it.chapter to it.verse },
        )
        assertEquals(
            listOf("Daniel" to 12 to 1),
            RefDetector.detectBible("Da 12:1").map { it.label to it.chapter to it.verse },
        )
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
