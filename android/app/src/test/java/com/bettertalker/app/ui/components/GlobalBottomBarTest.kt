package com.bettertalker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalBottomBarTest {

    @Test
    fun largura411efonteNormalUsaRotulosCompletos() {
        val layout = bottomBarLayout(411, 1.0f)
        assertTrue(layout.showLabels)
        assertEquals(bottomBarFullLabels, layout.labels)
    }

    @Test
    fun largura320comFonte15UsaIdentificacaoPorIcone() {
        val layout = bottomBarLayout(320, 1.5f)
        assertFalse(layout.showLabels)
        assertEquals(5, layout.labels.size)
    }

    @Test
    fun largura320comFonte20UsaIdentificacaoPorIcone() {
        val layout = bottomBarLayout(320, 2.0f)
        assertFalse(layout.showLabels)
    }

    @Test
    fun largura360comFonte13UsaRotulosCompactos() {
        val layout = bottomBarLayout(360, 1.3f)
        assertTrue(layout.showLabels)
        assertEquals(listOf("IA", "Bib.", "Prep.", "Treino", "Palco"), layout.labels)
    }

    @Test
    fun cincoAbasSaoPreservadas() {
        for (width in listOf(320, 360, 411)) {
            for (scale in listOf(1.0f, 1.3f, 1.5f, 2.0f)) {
                assertEquals(5, bottomBarLayout(width, scale).labels.size)
            }
        }
    }
}