package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B: HTML→texto cru para a prosa do dossiê (tags viram espaço, resto intacto). */
class TextNormHtmlTest {

    @Test
    fun htmlToPlainText_trocaTagsPorEspaco() {
        // Cada tag vira UM espaço: "</b></p>" no fim = dois espaços.
        val out = htmlToPlainText("<p>Olá <b>mundo</b></p>")

        assertEquals(" Olá  mundo  ", out)
        assertFalse(out.contains("<"))
        assertFalse(out.contains(">"))
    }

    @Test
    fun htmlToPlainText_textoSemTagsPassaIntacto() {
        assertEquals("texto puro", htmlToPlainText("texto puro"))
        assertEquals("", htmlToPlainText(""))
    }

    @Test
    fun htmlToPlainText_preservaQuebrasEntreBlocos() {
        val out = htmlToPlainText("<p>um</p><p>dois</p>")

        assertTrue(out.contains("um"))
        assertTrue(out.contains("dois"))
        assertFalse(out.contains("<p>"))
    }
}
