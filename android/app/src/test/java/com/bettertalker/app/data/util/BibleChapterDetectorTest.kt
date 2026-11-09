package com.bettertalker.app.data.util

import com.bettertalker.app.data.bible.displayBibleChaptersFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.2.1 — capítulos inteiros e faixas, sem confundir versículo/publicação. */
class BibleChapterDetectorTest {

    @Test
    fun capituloSimples() {
        val refs = RefDetector.detectBibleChapters("Jeremias 52")
        assertEquals(1, refs.size)
        assertEquals("Jeremias", refs[0].label)
        assertEquals(52, refs[0].chapter)
    }

    @Test
    fun versiculoComDoisPontosNaoViraCapitulo() {
        assertTrue(RefDetector.detectBibleChapters("Jeremias 52:2").isEmpty())
        assertTrue(RefDetector.detectBibleChapters("1 João 4:8").isEmpty())
    }

    @Test
    fun faixaDeCapitulosExpande() {
        val refs = RefDetector.detectBibleChapters("Jeremias 51-52")
        assertEquals(listOf(51, 52), refs.map { it.chapter })
    }

    @Test
    fun variacoesDeLivro() {
        assertEquals("Salmos", RefDetector.detectBibleChapters("Salmo 23").single().label)
        assertEquals("1 João", RefDetector.detectBibleChapters("1 João 4").single().label)
        assertEquals("Jó", RefDetector.detectBibleChapters("Jó 14").single().label)
        assertEquals("João", RefDetector.detectBibleChapters("João 14").single().label)
    }

    @Test
    fun publicacaoNaoViraCapitulo() {
        assertTrue(RefDetector.detectBibleChapters("w25.07").isEmpty())
        assertTrue(RefDetector.detectBibleChapters("Sentinela 2024").isEmpty())
        assertTrue(RefDetector.detectBibleChapters("Xyz 1").isEmpty())
    }

    @Test
    fun displayCompactaFaixas() {
        assertEquals(
            "Jeremias 51-52",
            displayBibleChaptersFor("Jeremias 51-52"),
        )
        assertEquals(
            "Jeremias 52; Salmos 23",
            displayBibleChaptersFor("Jeremias 52; Salmo 23"),
        )
    }
}
