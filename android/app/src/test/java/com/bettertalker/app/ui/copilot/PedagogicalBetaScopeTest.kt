package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEDAGOGY-1-MIN / P9 — congelamento do beta: `basicamente` exprime grau/
 * abrangência e deve ser preservado em qualquer posição. Fail-first no SHA
 * auditado `7b11ec001b6110dda6594106e8dadf444906230a`.
 */
class PedagogicalBetaScopeTest {

    @Test
    fun p9a_tresSondasDaCoordenacao() {
        listOf(
            "O trabalho está basicamente concluído.",
            "Ele concorda basicamente com a proposta.",
            "Basicamente, devemos demonstrar amor.",
        ).forEach { raw -> assertEquals("P9: $raw", raw, simplifyPedagogicalText(raw)) }
    }

    @Test
    fun p9b_basicamenteEmMeioFimComPontuacaoENegacao() {
        listOf(
            "O trabalho, basicamente, está concluído.",
            "O trabalho está concluído, basicamente.",
            "Basicamente? Sim.",
            "Não basicamente, mas talvez.",
            "BASICAMENTE, devemos demonstrar amor.",
        ).forEach { raw -> assertEquals("P9: $raw", raw, simplifyPedagogicalText(raw)) }
    }

    @Test
    fun p9c_marcadoresDiscursivosSegurosContinuamEfetivos() {
        assertEquals(
            "O texto é longo, precisa de revisão.",
            simplifyPedagogicalText("O texto é longo, ou seja, precisa de revisão."),
        )
        assertEquals(
            "o texto fala do amor.",
            simplifyPedagogicalText("Em outras palavras, o texto fala do amor."),
        )
        assertEquals(
            "o texto fala do amor.",
            simplifyPedagogicalText("Dito de outra forma, o texto fala do amor."),
        )
        assertEquals(
            "o texto fala do amor.",
            simplifyPedagogicalText("Em outros termos, o texto fala do amor."),
        )
    }

    @Test
    fun p9d_conectivosCondicoesNegacoesIntensidadeModalidadePreservados() {
        listOf(
            "Se chover, ficamos em casa.",
            "Porque choveu, ficamos em casa.",
            "Portanto, ficamos em casa.",
            "Mas ficamos em casa.",
            "Não ficamos em casa.",
            "Quase sempre ficamos em casa.",
            "Talvez ficássemos em casa.",
            "Exceto se chover, ficamos em casa.",
            "É importante ressaltar que basicamente ficamos em casa.",
        ).forEach { raw ->
            val out = simplifyPedagogicalText(raw)
            if (raw.startsWith("É importante ressaltar que")) {
                assertEquals("basicamente ficamos em casa.", out)
            } else {
                assertEquals("P9: $raw", raw, out)
            }
        }
    }

    @Test
    fun p9e_citacoesReferenciasERotuloComBasicamenteIntactos() {
        val rotulo = "Trecho recuperado: “amor paciente” — João 3:16"
        val out = simplifyPedagogicalText("Basicamente, $rotulo.")
        assertTrue(out.contains("Basicamente"))
        assertTrue(out.contains(rotulo))
        assertEquals(out, simplifyPedagogicalText(out))
        assertFalse(out.contains('\u0000'))
    }
}
