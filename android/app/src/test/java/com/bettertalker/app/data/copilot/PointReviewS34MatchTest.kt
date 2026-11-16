package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointReviewS34MatchTest {

    private fun section(id: String, order: Int, title: String) = S34Section(
        id = id,
        order = order,
        title = title,
        minutes = null,
        content = "",
        sourceLine = order,
    )

    @Test
    fun introducaoEConclusaoNoEsbocoNaoDeslocamOMatch() {
        val sections = listOf(
            section("intro", 1, "INTRODUÇÃO"),
            section("a", 2, "PRIMEIRO PONTO"),
            section("b", 3, "SEGUNDO PONTO"),
            section("fim", 4, "CONCLUSÃO"),
        )

        assertEquals("PRIMEIRO PONTO", matchS34SectionForPoint("PRIMEIRO PONTO", sections)?.title)
        assertEquals("SEGUNDO PONTO", matchS34SectionForPoint("SEGUNDO PONTO", sections)?.title)
    }

    @Test
    fun pontosNaOrdemCorretaCasamPorTitulo() {
        val sections = listOf(
            section("a", 1, "PRIMEIRO PONTO"),
            section("b", 2, "SEGUNDO PONTO"),
            section("c", 3, "TERCEIRO PONTO"),
        )

        assertEquals("PRIMEIRO PONTO", matchS34SectionForPoint("Primeiro ponto", sections)?.title)
        assertEquals("SEGUNDO PONTO", matchS34SectionForPoint("SEGUNDO PONTO", sections)?.title)
        assertEquals("TERCEIRO PONTO", matchS34SectionForPoint("Terceiro Ponto", sections)?.title)
    }

    @Test
    fun secaoAusenteOuDivergenteViraIndisponivel() {
        val sections = listOf(section("a", 1, "PRIMEIRO PONTO"))

        assertNull(matchS34SectionForPoint("SEGUNDO PONTO", sections))
        assertNull(matchS34SectionForPoint("PONTO RENOMEADO PELO USUÁRIO", sections))
    }

    @Test
    fun correspondenciaAmbiguaViraIndisponivel() {
        val sections = listOf(
            section("a1", 1, "PONTO REPETIDO"),
            section("a2", 2, "Ponto repetido"),
        )

        assertNull(matchS34SectionForPoint("PONTO REPETIDO", sections))
    }

    @Test
    fun normalizaAcentoCaixaPontuacaoEMinutos() {
        val sections = listOf(
            section("a", 1, "A organização de Jeová é unida"),
        )

        assertEquals(
            "A organização de Jeová é unida",
            matchS34SectionForPoint("A ORGANIZAÇÃO DE JEOVÁ É UNIDA (8 min)", sections)?.title,
        )
        assertEquals(
            "A organização de Jeová é unida",
            matchS34SectionForPoint("“A organização de Jeová é unida”", sections)?.title,
        )
    }

    @Test
    fun introducaoOuConclusaoNuncaCasam() {
        val sections = listOf(
            section("intro", 1, "INTRODUÇÃO"),
            section("a", 2, "PRIMEIRO PONTO"),
            section("fim", 3, "CONCLUSÃO"),
        )

        assertNull(matchS34SectionForPoint("INTRODUÇÃO", sections))
        assertNull(matchS34SectionForPoint("CONCLUSÃO", sections))
        assertEquals("PRIMEIRO PONTO", matchS34SectionForPoint("PRIMEIRO PONTO", sections)?.title)
    }

    @Test
    fun listaVaziaOuTituloEmBrancoViraIndisponivel() {
        assertNull(matchS34SectionForPoint("PRIMEIRO PONTO", null))
        assertNull(matchS34SectionForPoint("PRIMEIRO PONTO", emptyList()))
        assertNull(matchS34SectionForPoint("", listOf(section("a", 1, "PRIMEIRO PONTO"))))
        assertNull(matchS34SectionForPoint("   ", listOf(section("a", 1, "PRIMEIRO PONTO"))))
    }

    @Test
    fun naoUsaIndiceQuandoTituloDiverge() {
        val sections = listOf(section("a", 1, "PRIMEIRO PONTO"))

        // O índice 0 existiria, mas entregaria a seção errada.
        assertNull(matchS34SectionForPoint("SEGUNDO PONTO", sections))
    }
}
