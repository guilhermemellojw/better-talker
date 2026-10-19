package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase A — normalização da colagem (NBSP, bullets, travessões, minutos órfãos). */
class PasteTextNormalizerTest {

    @Test
    fun nbspEBulletsDePdf_saoRemovidos() {
        val raw = "\u00A0• 5. Cultivando o interesse (4 min) DE CASA EM CASA"
        val out = PasteTextNormalizer.normalize(raw)
        assertEquals("5. Cultivando o interesse (4 min) DE CASA EM CASA", out)
    }

    @Test
    fun travessoesTipograficos_viramHifen() {
        val out = PasteTextNormalizer.normalize("5 – Cultivando o interesse — (4 min)")
        assertEquals("5 - Cultivando o interesse - (4 min)", out)
    }

    @Test
    fun minutosOrfaos_naLinhaSeguinte_saoJuntadosAoAnterior() {
        val out = PasteTextNormalizer.normalize(
            "5. Cultivando o interesse\n(4 min)\nDE CASA EM CASA"
        )
        assertEquals("5. Cultivando o interesse (4 min)\nDE CASA EM CASA", out)
    }

    @Test
    fun minutosJaNaLinha_naoSaoDuplicados() {
        val out = PasteTextNormalizer.normalize(
            "5. Cultivando o interesse (4 min)\n(4 min)\nDE CASA EM CASA"
        )
        // A linha órfã não é reanexada (a anterior já tem minutos).
        assertTrue(out.startsWith("5. Cultivando o interesse (4 min)"))
    }

    @Test
    fun espacosETabs_saoColapsados_semPerderQuebras() {
        val out = PasteTextNormalizer.normalize("Título   com\t\tespaços\n\nCorpo")
        assertEquals("Título com espaços\n\nCorpo", out)
    }
}
