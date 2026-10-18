package com.bettertalker.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** Texturas de fundo: parse do valor persistido + rótulos (puros). */
class TextureIdTest {

    @Test
    fun fromNameOrDefault_conhecidosEUnknown() {
        assertEquals(TextureId.DOTS, TextureId.fromNameOrDefault("DOTS"))
        assertEquals(TextureId.PAPER, TextureId.fromNameOrDefault("PAPER"))
        assertEquals(TextureId.GRID, TextureId.fromNameOrDefault("GRID"))
        assertEquals(TextureId.NOISE, TextureId.fromNameOrDefault("NOISE"))
        assertEquals(TextureId.NONE, TextureId.fromNameOrDefault("???"))
        assertEquals(TextureId.NONE, TextureId.fromNameOrDefault(null))
    }

    @Test
    fun rotulos() {
        assertEquals("Sem textura", textureLabel(TextureId.NONE))
        assertEquals("Papel", textureLabel(TextureId.PAPER))
        assertEquals("Pontilhado", textureLabel(TextureId.DOTS))
        assertEquals("Quadriculado", textureLabel(TextureId.GRID))
        assertEquals("Ruído", textureLabel(TextureId.NOISE))
    }
}
