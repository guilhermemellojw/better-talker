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


    @Test
    fun destinoUsaRotuloTextual() {
        assertEquals("Destino: Ponto 2", destinationActionLabel("Ponto 2"))
        assertEquals("Destino: abcdefghijklmnopqrstuvwxyz12", destinationActionLabel("abcdefghijklmnopqrstuvwxyz1234"))
    }

}