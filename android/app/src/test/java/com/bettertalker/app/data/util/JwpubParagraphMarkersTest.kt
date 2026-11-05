package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JWPUB antigo: numeração por ParagraphNumberLabel ou fallback ParagraphIndex. */
class JwpubParagraphMarkersTest {

    @Test
    fun usaLabelQuandoExiste() {
        val rows = listOf(
            Triple(1, null, 10),
            Triple(3, 1, 543),
            Triple(4, 2, 1435),
            Triple(8, null, 4748), // subtítulo sem número
        )
        assertEquals(
            listOf(1 to 543, 2 to 1435),
            JwpubExtractor.paragraphMarkersFromRows(rows),
        )
    }

    @Test
    fun semNenhumLabelUsaIndiceMenosUmESkipaTitulo() {
        val rows = listOf(
            Triple(1, null, 10),
            Triple(2, null, 179),
            Triple(11, null, 8740),
        )
        assertEquals(
            listOf(1 to 179, 10 to 8740),
            JwpubExtractor.paragraphMarkersFromRows(rows),
        )
    }

    @Test
    fun insereMarcadoresDaFimParaOFim() {
        val html = "<p>Primeiro</p><p>Segundo</p><p>Terceiro</p>"
        val first = html.indexOf("Primeiro")
        val second = html.indexOf("Segundo")
        val marked = JwpubExtractor.insertParagraphMarkers(
            html,
            listOf(1 to first, 2 to second),
        )
        assertTrue(marked.contains("§1 Primeiro"))
        assertTrue(marked.contains("§2 Segundo"))
        assertTrue(marked.indexOf("§1") < marked.indexOf("§2"))
    }

    @Test
    fun fallbackPorPidUsaP2ComoParagrafo1() {
        val html = "<p id=\"p1\" data-pid=\"1\">Título</p>" +
            "<p id=\"p2\" data-pid=\"2\">Primeiro</p>" +
            "<p id=\"p11\" data-pid=\"11\">Décimo</p>"
        val marked = JwpubExtractor.insertParagraphMarkersByPid(html)
        assertTrue(marked.contains("§1 Primeiro") || marked.contains("§1 \nPrimeiro"))
        assertTrue(marked.contains("§10"))
    }
}
