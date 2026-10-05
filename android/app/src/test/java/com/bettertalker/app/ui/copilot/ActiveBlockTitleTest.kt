package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T2 (Bug #10) — normalização do foco do editor (seção ativa). */
class ActiveBlockTitleTest {

    @Test
    fun tituloEhTrimmado() {
        assertEquals("FOMOS CRIADOS PARA VIVER", normalizeActiveBlockTitle("  FOMOS CRIADOS PARA VIVER  "))
    }

    @Test
    fun brancoOuNuloViraNull() {
        assertNull(normalizeActiveBlockTitle("   "))
        assertNull(normalizeActiveBlockTitle(""))
        assertNull(normalizeActiveBlockTitle(null))
    }
}
