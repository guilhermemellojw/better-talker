package com.bettertalker.app.data.s34

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5a — NOTA do orador: captura do bloco (persona) no S34Parser, nas três
 * variações reais (NOTA:, *Nota ao orador:* com quote, ausência) e nas
 * bordas (dupla em branco + cabeçalho, múltiplas linhas, dois blocos).
 */
class S34SpeakerNoteTest {

    private val section = """
FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)

Precisamos estar vivos para ter esperança. (Gên 1:26)
    """.trimIndent()

    @Test
    fun notaSimplesNoTopo_captura() {
        val doc = S34Parser.parseS34(
            """
            N.º 35 É possível viver para sempre?

            NOTA: Ajude a assistência a meditar em como vai ser maravilhoso viver para sempre.

            $section
            """.trimIndent()
        )
        assertEquals(
            "Ajude a assistência a meditar em como vai ser maravilhoso viver para sempre.",
            doc.speakerNote,
        )
        // Não vaza para o título/cabeçalho.
        assertFalse(doc.title.contains("Ajude a assistência"))
    }

    @Test
    fun notaAoOradorComItalicoEQuote_limpaMarcadores() {
        val doc = S34Parser.parseS34(
            """
            N.º 187 Discurso de exemplo

            *Nota ao orador:*
            > Apresente respostas claras para os novos.

            $section
            """.trimIndent()
        )
        assertEquals("Apresente respostas claras para os novos.", doc.speakerNote)
    }

    @Test
    fun esbozoSemNota_speakerNoteNull() {
        val doc = S34Parser.parseS34(
            """
            N.º 152 Discurso antigo

            $section
            """.trimIndent()
        )
        assertNull(doc.speakerNote)
    }

    @Test
    fun duplaEmBrancoAntesDoCabecalho_naoVazaParaASecao() {
        val doc = S34Parser.parseS34(
            """
            NOTA: Primeira parte da orientação.


            FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)

            Precisamos estar vivos para ter esperança.
            """.trimIndent()
        )
        assertEquals("Primeira parte da orientação.", doc.speakerNote)
        assertEquals("FOMOS CRIADOS PARA VIVER PARA SEMPRE", doc.sections.single().title)
        assertFalse(doc.sections.single().content.contains("Primeira parte da orientação"))
    }

    @Test
    fun multiplasLinhas_preservaParagrafosComDuasQuebras() {
        val doc = S34Parser.parseS34(
            """
            NOTA: Primeira linha
            segunda linha do mesmo parágrafo.

            Novo parágrafo da nota.


            FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)

            Corpo.
            """.trimIndent()
        )
        assertEquals(
            "Primeira linha segunda linha do mesmo parágrafo.\n\nNovo parágrafo da nota.",
            doc.speakerNote,
        )
    }

    @Test
    fun doisBlocos_concatenaComDuasQuebras() {
        val doc = S34Parser.parseS34(
            """
            Nota ao orador: Primeira nota.

            NOTA: Segunda nota.

            FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)

            Corpo.
            """.trimIndent()
        )
        assertTrue(doc.speakerNote!!.contains("Primeira nota."))
        assertTrue(doc.speakerNote!!.contains("Segunda nota."))
        assertEquals("Primeira nota.\n\nSegunda nota.", doc.speakerNote)
    }

    @Test
    fun notaAntesDeObjetivo_naoCapturaObjetivoJunto() {
        val doc = S34Parser.parseS34(
            """
            NOTA: Fale com bondade.

            Objetivo:
            Mostrar o valor da esperança.

            FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)

            Corpo.
            """.trimIndent()
        )
        assertEquals("Fale com bondade.", doc.speakerNote)
        assertEquals("Mostrar o valor da esperança.", doc.objective)
    }
}
