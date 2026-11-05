package com.bettertalker.app.data.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.4 — a lente do fio mostra nota inteira, foco e vizinhas. */
class NoteLensBlockTest {
    private val sections = listOf(
        NoteLensSection("s1", "Introdução", 2),
        NoteLensSection("s2", "Desenvolvimento", 10),
        NoteLensSection("s3", "Conclusão", 3),
    )

    @Test
    fun topicoTrazNotaFocoEVizinhas() {
        val block = buildNoteLensBlock(
            noteTitle = "A esperança que não decepciona",
            sections = sections,
            focusId = "s2",
            whole = false,
        )
        assertTrue(block.contains("## NOTA"))
        assertTrue(block.contains("A esperança que não decepciona"))
        assertTrue(block.contains("Tópico 2/3: Desenvolvimento (10 min)"))
        assertTrue(block.contains("Anterior: Introdução"))
        assertTrue(block.contains("Próxima: Conclusão"))
        assertTrue(block.contains("Você vê a nota inteira"))
        assertTrue(block.contains("apenas o bloco em foco"))
    }

    @Test
    fun discursoInteiroEhPlanejamentoNaoDespejo() {
        val block = buildNoteLensBlock(
            noteTitle = "A esperança que não decepciona",
            sections = sections,
            focusId = null,
            whole = true,
        )
        assertTrue(block.contains("Discurso inteiro"))
        assertTrue(block.contains("planejamento e coerência"))
        assertTrue(block.contains("não para despejar o texto todo"))
        assertFalse(block.contains("SEÇÕES VIZINHAS"))
    }

    @Test
    fun notaSemSecoesAindaEhExplicita() {
        val block = buildNoteLensBlock("Nota", emptyList(), focusId = null, whole = false)
        assertTrue(block.contains("nota sem seções persistidas"))
        assertTrue(block.contains("Tópico atual (sem seção identificada)"))
    }
}
