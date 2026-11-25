package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCardsPresentationTest {

    @Test
    fun acaoDeGerarTemRotuloMesmoOcupado() {
        assertEquals("Gerar", generateActionLabel(false))
        assertEquals("Gerando…", generateActionLabel(true))
    }

    @Test
    fun gerarIdeiasMantemRotuloDuranteCarregamento() {
        assertEquals("Gerar ideias", generateIdeasActionLabel(false))
        assertEquals("Gerando ideias…", generateIdeasActionLabel(true))
    }

}