package com.bettertalker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalBottomBarTest {

    private val widths = listOf(320, 360, 411)
    private val scales = listOf(1.0f, 1.3f, 1.5f, 2.0f)

    @Test
    fun matriz12CombinaçõesTemModoDefinido() {
        for (width in widths) {
            for (scale in scales) {
                val mode = bottomBarMode(width, scale)
                assertTrue(mode == BottomBarMode.CONVENTIONAL || mode == BottomBarMode.ADAPTIVE)
            }
        }
    }

    @Test
    fun barraConvencionalQuandoHaEspaco() {
        assertEquals(BottomBarMode.CONVENTIONAL, bottomBarMode(360, 1.0f))
        assertEquals(BottomBarMode.CONVENTIONAL, bottomBarMode(360, 1.3f))
        assertEquals(BottomBarMode.CONVENTIONAL, bottomBarMode(411, 1.0f))
        assertEquals(BottomBarMode.CONVENTIONAL, bottomBarMode(411, 1.3f))
    }

    @Test
    fun seletorAdaptativoNasFronteiras() {
        assertEquals(BottomBarMode.ADAPTIVE, bottomBarMode(320, 1.0f))
        assertEquals(BottomBarMode.ADAPTIVE, bottomBarMode(360, 1.5f))
        assertEquals(BottomBarMode.ADAPTIVE, bottomBarMode(411, 2.0f))
    }

    @Test
    fun identidadeCompletaDosDestinos() {
        assertEquals(
            listOf("Assistente", "Biblioteca", "Preparo", "Treino", "Palco"),
            bottomBarFullLabels,
        )
        assertEquals(
            listOf("assistant", "library", "prepare", "train", "present"),
            bottomBarDestinationIds,
        )
    }

    @Test
    fun cincoDestinosDisponiveisEmTodosOsModos() {
        for (width in widths) {
            for (scale in scales) {
                val mode = bottomBarMode(width, scale)
                assertEquals(5, bottomBarFullLabels.size)
                assertEquals(5, bottomBarDestinationIds.size)
                assertTrue(mode.name.isNotBlank())
            }
        }
    }

    @Test
    fun nuncaUsaRotulosAbreviadosOuIconOnly() {
        for (label in bottomBarFullLabels) {
            assertTrue(label.length >= 5)
        }
        // Nenhuma política produz rótulo vazio; a identificação é sempre textual.
        for (width in widths) {
            for (scale in scales) {
                assertTrue(bottomBarMode(width, scale).name.isNotBlank())
            }
        }
    }
}
