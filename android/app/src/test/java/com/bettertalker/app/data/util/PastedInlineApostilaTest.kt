package com.bettertalker.app.data.util

import com.bettertalker.app.data.s34.S34Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.7.5 — Formato A: apostila inline (1 título com tempo + N pontos com refs). */
class PastedInlineApostilaTest {

    private val text = """
Ter esperança é o segredo para o contentamento (10 min)
Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)
Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)
Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)
""".trimIndent()

    @Test
    fun formatA_1secao3Pontos() {
        val p = PastedOutlineAnalyzer.inlineApostila(text)
        assertNotNull(p)
        assertEquals("Ter esperança é o segredo para o contentamento", p!!.title)
        assertEquals(3, p.points.size)
        assertTrue(p.body.contains("Baruque"))
        assertTrue(p.body.contains("w25.07 22 §§ 8-9"))
        assertTrue(p.body.contains("w25.07 24 §§ 13-14"))
    }

    @Test
    fun formatA_refsExtraidas() {
        val refs = RefDetector.detect(text)
        assertEquals(3, refs.size)
        assertEquals(listOf(22, 20, 24), refs.map { it.page })
        assertEquals(listOf(8, 2, 13), refs.map { it.paragraph })
        assertEquals(listOf(9, null, 14), refs.map { it.paragraphEnd })
        val bible = RefDetector.detectBible(text)
        assertEquals(2, bible.size)
    }

    @Test
    fun formatA_semTempo() {
        assertNull(
            PastedOutlineAnalyzer.inlineApostila(
                "Titulo qualquer\nPonto (w25.07 22 § 2)"
            )
        )
    }

    @Test
    fun formatA_multiplosPontos() {
        val t = "Titulo grande o suficiente (10 min)\n" +
            (1..5).joinToString("\n") { "Ponto $it (w25.07 22 § $it)" }
        val p = PastedOutlineAnalyzer.inlineApostila(t)
        assertNotNull(p)
        assertEquals(5, p!!.points.size)
        assertEquals("Titulo grande o suficiente", p.title)
    }

    @Test
    fun formatA_1Ponto() {
        val t = "Titulo grande o suficiente (5 min)\nPonto unico (w25.07 22 § 2)"
        val p = PastedOutlineAnalyzer.inlineApostila(t)
        assertNotNull(p)
        assertEquals(1, p!!.points.size)
    }

    @Test
    fun formatB_naoRegride() {
        val b = "Titulo A grande (3 min)\ncorpo A\nTitulo B grande (7 min)\ncorpo B"
        assertNull(PastedOutlineAnalyzer.inlineApostila(b))
        val (cands, _) = PastedOutlineAnalyzer.candidates(b)
        assertTrue("candidatos: ${cands.map { it.text }}", cands.size >= 2)
    }

    @Test
    fun formatC_naoRegride() {
        val s = "Tema: X\n1. Ponto (w25.07 22 § 2)\n"
        assertNull(PastedOutlineAnalyzer.inlineApostila(s))
        val doc = S34Parser.parseS34(s)
        assertTrue(doc.sections.isNotEmpty())
    }
}
