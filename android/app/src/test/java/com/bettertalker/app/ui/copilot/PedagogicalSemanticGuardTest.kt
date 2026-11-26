package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PEDAGOGY-1-MIN / P8 — F9: qualificadores com valor semântico não podem
 * ser suprimidos pela simplificação. Fail-first no SHA auditado `7b455856`.
 */
class PedagogicalSemanticGuardTest {

    // P9/beta: `basicamente` também é qualificador (grau/abrangência) e foi
    // retirado da lista removível; os testes que exigiam sua remoção foram
    // revisados explicitamente e registrados no PR.
    private val qualificadores = listOf(
        "Em geral", "De certa forma", "Literalmente",
        "Simplesmente", "Na verdade", "Por assim dizer", "Em suma", "Em resumo",
    )

    @Test
    fun f9a_tresCasosDeE_noInicio() {
        listOf(
            "Em geral, essa orientação se aplica.",
            "De certa forma, isso é verdade.",
            "Literalmente, ele disse isso.",
        ).forEach { assertEquals("F9: $it", it, simplifyPedagogicalText(it)) }
    }

    @Test
    fun f9b_qualificadoresNoInicioMeioEFim() {
        qualificadores.forEach { q ->
            val minusculo = q.replaceFirstChar { it.lowercase() }
            listOf(
                "$q, essa orientação se aplica.",
                "Essa orientação, $minusculo, se aplica.",
                "Essa orientação se aplica, $minusculo.",
            ).forEach { raw -> assertEquals("F9: $raw", raw, simplifyPedagogicalText(raw)) }
        }
    }

    @Test
    fun f9c_negacaoCondicaoIntensidadeEExcecao() {
        listOf(
            "Não é verdade, em geral.",
            "Se, em geral, isso se aplica, siga em frente.",
            "Somente de certa forma isso é verdade.",
            "Exceto quando literalmente ele disse isso.",
            "Apenas na verdade isso se aplica.",
        ).forEach { assertEquals("F9: $it", it, simplifyPedagogicalText(it)) }
    }

    @Test
    fun f9d_marcadoresDiscursivosSegurosContinuamEfetivos() {
        assertEquals("Basicamente, o texto fala do amor.", simplifyPedagogicalText("Basicamente, o texto fala do amor."))
        assertEquals("o texto fala do amor.", simplifyPedagogicalText("Em outras palavras, o texto fala do amor."))
        assertEquals("o texto fala do amor.", simplifyPedagogicalText("Ou seja, o texto fala do amor."))
        assertEquals("o texto fala do amor.", simplifyPedagogicalText("Dito de outra forma, o texto fala do amor."))
        assertEquals("para explicar, o texto fala do amor.",
            simplifyPedagogicalText("Com o objetivo de explicar, o texto fala do amor."))
        assertEquals("João 3:16 fala do amor.",
            simplifyPedagogicalText("É importante ressaltar que João 3:16 fala do amor."))
    }

    @Test
    fun f9e_preservacaoF8SegueIntacta() {
        val rotulo = "Trecho recuperado: “amor paciente” — João 3:16"
        val out = simplifyPedagogicalText("Basicamente, em outras palavras, $rotulo.")
        assertEquals("Basicamente, $rotulo.", out)
    }
}
