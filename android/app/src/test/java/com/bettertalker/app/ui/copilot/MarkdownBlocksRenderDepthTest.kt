package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #22-fix-b — marcador do render por profundidade (a renderização Compose em
 * si é validada no device). Puro/testável.
 */
class MarkdownBlocksRenderDepthTest {

    @Test
    fun subUsaCirculo() {
        assertEquals("○  ", bulletMarker(null, 1))
        assertEquals("○  ", bulletMarker(null, 2))
    }

    @Test
    fun topoMantemBullet() {
        assertEquals("•  ", bulletMarker(null, 0))
    }

    @Test
    fun numeroEhPreservadoEmQualquerNivel() {
        assertEquals("3.  ", bulletMarker(3, 0))
        assertEquals("3.  ", bulletMarker(3, 1))
    }
}
