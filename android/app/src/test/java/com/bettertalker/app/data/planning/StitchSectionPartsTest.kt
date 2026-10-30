package com.bettertalker.app.data.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StitchSectionPartsTest {

    @Test
    fun juntaParagrafosNaOrdem() {
        assertEquals(
            "<p>Primeiro.</p><p>Segundo.</p>",
            stitchSectionParts(listOf("<p>Primeiro.</p>", "<p>Segundo.</p>")),
        )
    }

    @Test
    fun removeParagrafoDuplicadoConsecutivo() {
        val out = stitchSectionParts(
            listOf(
                "<p>Um.</p><p>Dois.</p>",
                "<p>Dois.</p><p>Três.</p>",
            ),
        )
        assertEquals("<p>Um.</p><p>Dois.</p><p>Três.</p>", out)
    }

    @Test
    fun cortaNaUltimaFraseCompleta() {
        val out = stitchSectionParts(
            listOf("<p>Primeira frase. Segunda ainda não terminou"),
            cutLast = true,
        )
        assertEquals("<p>Primeira frase.</p>", out)
    }

    @Test
    fun cortaEFechaStrongAberto() {
        val out = stitchSectionParts(
            listOf("<p><strong>Frase completa. Outra incompleta"),
            cutLast = true,
        )
        assertTrue(out.contains("Frase completa."))
        assertTrue(out.endsWith("</strong></p>"))
    }

    @Test
    fun htmlInternoEhPreservado() {
        val out = stitchSectionParts(
            listOf("<p>Uma <strong>frase</strong> completa. Outra</p>"),
            cutLast = true,
        )
        assertEquals("<p>Uma <strong>frase</strong> completa.</p>", out)
    }

    @Test
    fun textoVazioNaoGeraParagrafo() {
        assertEquals("", stitchSectionParts(emptyList()))
        assertEquals("", stitchSectionParts(listOf("", "   ")))
    }

    @Test
    fun textoSemTagsViraParagrafo() {
        val out = stitchSectionParts(listOf("Primeira. Segunda."))
        assertEquals("<p>Primeira. Segunda.</p>", out)
        assertFalse(out.contains("<p><p>"))
    }
}
