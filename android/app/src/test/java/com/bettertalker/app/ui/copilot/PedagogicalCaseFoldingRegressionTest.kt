package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOTFIX-68 / P0.1 — regressões de case folding do parecer de E
 * (comentário `7191119390`) no HEAD bloqueado `497e4ad8`.
 *
 * F1: rótulo de proveniência em caixa alta/capitalização mista deve ser
 * preservado literalmente (inclusive o marcador dentro dele).
 * F2: guardas de negação/condição/qualificação em caixa alta/mista devem
 * impedir a remoção do marcador de reformulação seguinte.
 */
class PedagogicalCaseFoldingRegressionTest {

    @Test
    fun f1_provenienciaCaixaAltaPreservadaLiteralmente() {
        listOf(
            "TRECHO RECUPERADO: em outras palavras, o texto diz amor.",
            "TRECHO RECUPERADO: ou seja, o texto diz amor.",
            "Trecho Recuperado: em outras palavras, o texto diz amor.",
            "tRECHO rECUPERADO: ou seja, o texto diz amor.",
            "trecho recuperado: em outras palavras, o texto diz amor.",
        ).forEach { raw -> assertEquals("F1: $raw", raw, simplifyPedagogicalText(raw)) }
    }

    @Test
    fun f2_guardasCaixaAltaEMistapreservadas() {
        listOf(
            "NÃO em outras palavras concordo.",
            "NEM em outras palavras concordo.",
            "SE em outras palavras chover ficamos.",
            "CASO em outras palavras chova ficamos.",
            "SOMENTE em outras palavras funciona.",
            "NUNCA em outras palavras concordo.",
            "APENAS em outras palavras funciona.",
            "SÓ em outras palavras funciona.",
            "TALVEZ em outras palavras funcione.",
            "nÃO em outras palavras concordo.",
            "NãO em outras palavras concordo.",
            "sE em outras palavras chover ficamos.",
            "cASO em outras palavras chova ficamos.",
            "nUNCA em outras palavras concordo.",
        ).forEach { raw -> assertEquals("F2: $raw", raw, simplifyPedagogicalText(raw)) }
    }

    @Test
    fun f3_guardasMinusculasETituloContinuas() {
        listOf(
            "Não em outras palavras concordo.",
            "Nem em outras palavras concordo.",
            "Se em outras palavras chover ficamos.",
            "Caso em outras palavras chova ficamos.",
            "Somente em outras palavras funciona.",
            "Nunca em outras palavras concordo.",
            "Apenas em outras palavras funciona.",
            "Só em outras palavras funciona.",
            "Talvez em outras palavras funcione.",
        ).forEach { raw -> assertEquals("F3: $raw", raw, simplifyPedagogicalText(raw)) }
    }

    @Test
    fun f4_provenienciaComCitacaoReferenciaBasicamenteENul() {
        val rotulo = "TRECHO RECUPERADO: “amor paciente” — João 3:16"
        val raw = "Basicamente, $rotulo."
        val out = simplifyPedagogicalText(raw)
        assertEquals(raw, out)
        assertTrue(out.contains(rotulo))
        assertTrue(out.contains("Basicamente"))
        assertFalse(out.contains('\u0000'))
        assertEquals(out, simplifyPedagogicalText(out))
    }

    @Test
    fun f5_simplificacaoLegitimaSegueEfetiva() {
        assertEquals(
            "o texto fala do amor.",
            simplifyPedagogicalText("Em outras palavras, o texto fala do amor."),
        )
        assertEquals(
            "João 3:16 fala do amor.",
            simplifyPedagogicalText("É importante ressaltar que João 3:16 fala do amor."),
        )
        assertEquals(
            "O trabalho está basicamente concluído.",
            simplifyPedagogicalText("O trabalho está basicamente concluído."),
        )
    }
}
