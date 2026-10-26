package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1 — prompt da estrutura dividida em partes (> 30 min). Puro/testável. */
class StructurePartPromptTest {

    @Test
    fun semPartes_usaOPromptNormal() {
        val prompt = applyStructurePartMessage(
            pastedOutline = null,
            part = 1,
            parts = 1,
            titles = emptyList(),
        )
        assertEquals(applyStructureMessage(null), prompt)
        assertFalse(prompt.contains("DIVISÃO OBRIGATÓRIA"))
    }

    @Test
    fun parteUnica_listaSomenteOsTitulosDaParte() {
        val prompt = applyStructurePartMessage(
            pastedOutline = "Esboço original",
            part = 1,
            parts = 2,
            titles = listOf("INTRODUÇÃO", "Ponto A"),
        )
        assertTrue(prompt.contains("parte 1 de 2"))
        assertTrue(prompt.contains("- INTRODUÇÃO"))
        assertTrue(prompt.contains("- Ponto A"))
        assertFalse(prompt.contains("- Ponto B"))
        assertTrue(prompt.contains("Esboço original"))
    }

    @Test
    fun ultimaParte_recebeAConclusao() {
        val prompt = applyStructurePartMessage(
            pastedOutline = null,
            part = 2,
            parts = 2,
            titles = listOf("Ponto B", "CONCLUSÃO"),
        )
        assertTrue(prompt.contains("parte 2 de 2"))
        assertTrue(prompt.contains("- CONCLUSÃO"))
        assertTrue(prompt.contains("estrutura completa foi dividida"))
    }
}
