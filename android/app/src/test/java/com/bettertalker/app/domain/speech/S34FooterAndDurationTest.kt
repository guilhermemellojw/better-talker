package com.bettertalker.app.domain.speech

import com.bettertalker.app.data.util.OutlineParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 2 — rodapé/cobertura e normalização de duração do S-34. */
class S34FooterAndDurationTest {

    private val n192 = """
        N.° 192 Você está no caminho para a vida eterna?

        NOTA: Ajude os estudantes da Bíblia.

        PRIMEIRO TÓPICO (5 min)

        Primeiro ponto legítimo.

        SEGUNDO TÓPICO (13 min)

        Segundo ponto legítimo.

        TERCEIRO TÓPICO (7 min)

        Terceiro ponto legítimo.

        QUARTO TÓPICO (5 min)

        Quarto ponto legítimo.

        A SER ABRANGIDO EM 30 MINUTOS

        N.° 192-T 8/15
    """.trimIndent()

    @Test
    fun parse_coberturaDeclaraTotalEDescartaRodape() {
        val parsed = OutlineParser.parse(n192, "S-34_T_192.jwpub")

        assertEquals(30, parsed.totalMinutes)
        assertEquals(listOf(5, 13, 7, 5), parsed.sections.map { it.minutes })
        val lastBody = parsed.sections.last().body
        assertTrue(lastBody, lastBody.contains("Quarto ponto legítimo."))
        assertFalse("cobertura não pode virar corpo", lastBody.contains("A SER ABRANGIDO"))
        assertFalse("rodapé N.° …-T não pode virar corpo", lastBody.contains("192-T"))
    }

    @Test
    fun converter_fechaTotalDeclaradoComBordas() {
        val parsed = OutlineParser.parse(n192, "S-34_T_192.jwpub")
        val conv = OutlineConverter().convert(parsed, noteId = "n1")

        assertEquals("Introdução", conv.intro?.title)
        assertEquals("Conclusão", conv.conclusion?.title)
        assertEquals(listOf(5, 11, 7, 5), conv.bodies.map { it.section.minutes })
        assertEquals(
            "30 = intro + corpos + conclusão",
            30,
            (conv.intro?.minutes ?: 0) +
                conv.bodies.sumOf { it.section.minutes } +
                (conv.conclusion?.minutes ?: 0),
        )
        val allText = conv.bodies.flatMap { b -> b.subPoints.map { it.outlineText } }
        assertTrue(allText.any { it.contains("Quarto ponto legítimo.") })
        assertTrue(allText.none { it.contains("A SER ABRANGIDO") })
        assertTrue(allText.none { it.contains("192-T") })
    }

    @Test
    fun normalizeBodyMinutes_reduzDoMaiorEPreservaMinimo() {
        assertEquals(
            listOf(5, 11, 7, 5),
            normalizeBodyMinutes(listOf(5, 13, 7, 5), totalMinutes = 30, edgeMinutes = 2),
        )
        assertEquals(
            listOf(1, 1, 1),
            normalizeBodyMinutes(listOf(5, 1, 1), totalMinutes = 5, edgeMinutes = 2),
        )
    }

    @Test
    fun normalizeBodyMinutes_semTotalMantemOriginal() {
        assertEquals(
            listOf(5, 13, 7, 5),
            normalizeBodyMinutes(listOf(5, 13, 7, 5), totalMinutes = null, edgeMinutes = 2),
        )
    }
}
