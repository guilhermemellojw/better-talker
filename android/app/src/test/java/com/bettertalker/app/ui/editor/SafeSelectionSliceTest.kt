package com.bettertalker.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1: o corte da seleção do editor nunca estoura — índices defasados do
 * debounce (para trás ou além do fim) são clampados e ordenados.
 */
class SafeSelectionSliceTest {

    @Test
    fun selecaoNormal_funciona() {
        assertEquals("ab", safeSelectionSlice("abcd", 0, 2))
    }

    @Test
    fun selecaoInvertida_naoEstoura() {
        // Caso do crash real: begin 633, end 464 (seleção defasada p/ trás).
        assertEquals("bc", safeSelectionSlice("abcd", 3, 1))
    }

    @Test
    fun indicesAlemDoFim_clampam() {
        assertEquals("cd", safeSelectionSlice("abcd", 10, 2))
    }

    @Test
    fun indicesNegativos_clamam() {
        assertEquals("", safeSelectionSlice("abcd", -5, -1))
    }

    @Test
    fun textoVazio_naoEstoura() {
        assertEquals("", safeSelectionSlice("", 5, 2))
    }

    @Test
    fun tetoDe2000Chars() {
        val big = "x".repeat(5000)
        assertEquals(2000, safeSelectionSlice(big, 0, 5000).length)
    }
}
