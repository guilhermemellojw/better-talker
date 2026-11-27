package com.bettertalker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalBottomBarTest {

    private val widths = listOf(320, 360, 411)
    private val scales = listOf(1.0f, 1.3f, 1.5f, 2.0f)

    /** Expectativas independentes da implementação (matriz física A15/P4). */
    private val expectedModes = mapOf(
        320 to mapOf(
            1.0f to BottomBarMode.ADAPTIVE,
            1.3f to BottomBarMode.ADAPTIVE,
            1.5f to BottomBarMode.ADAPTIVE,
            2.0f to BottomBarMode.ADAPTIVE,
        ),
        360 to mapOf(
            1.0f to BottomBarMode.CONVENTIONAL,
            1.3f to BottomBarMode.ADAPTIVE,
            1.5f to BottomBarMode.ADAPTIVE,
            2.0f to BottomBarMode.ADAPTIVE,
        ),
        411 to mapOf(
            1.0f to BottomBarMode.CONVENTIONAL,
            1.3f to BottomBarMode.ADAPTIVE,
            1.5f to BottomBarMode.ADAPTIVE,
            2.0f to BottomBarMode.ADAPTIVE,
        ),
    )

    @Test
    fun matrizFisica12Combinações() {
        for (width in widths) {
            for (scale in scales) {
                assertEquals(
                    "largura=$width escala=$scale",
                    expectedModes[width]!![scale],
                    bottomBarMode(width, scale),
                )
            }
        }
    }

    @Test
    fun fronteira360x13Adaptativa() {
        assertEquals(BottomBarMode.ADAPTIVE, bottomBarMode(360, 1.3f))
    }

    @Test
    fun fronteira411x13Adaptativa() {
        assertEquals(BottomBarMode.ADAPTIVE, bottomBarMode(411, 1.3f))
    }

    @Test
    fun convencionalSomenteEm10() {
        assertEquals(BottomBarMode.CONVENTIONAL, bottomBarMode(360, 1.0f))
        assertEquals(BottomBarMode.CONVENTIONAL, bottomBarMode(411, 1.0f))
    }

    @Test
    fun largura320SempreAdaptativa() {
        for (scale in scales) {
            assertEquals(BottomBarMode.ADAPTIVE, bottomBarMode(320, scale))
        }
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
                assertEquals(5, bottomBarFullLabels.size)
                assertEquals(5, bottomBarDestinationIds.size)
                assertTrue(bottomBarMode(width, scale).name.isNotBlank())
            }
        }
    }

    @Test
    fun nuncaUsaRotulosAbreviadosOuIconOnly() {
        for (label in bottomBarFullLabels) {
            assertTrue(label.length >= 5)
        }
    }
}
