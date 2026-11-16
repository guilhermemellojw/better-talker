package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointReviewCodecTest {

    private val report = PointReviewReport(
        sectionId = "section-2",
        pointNumber = 2,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = 4,
        overall = DiagnosisLevel.ATTENTION,
        reading = "Ponto 2 · 4 min · 1/2 subpontos",
        strengths = listOf("O ponto tem 2 subpontos.", "Há 30 palavras."),
        improvements = listOf(
            "Desenvolva o segundo subponto.",
            "Inclua uma aplicação prática.",
        ),
        nextStep = PointReviewNextStep.OpenEditor(
            sectionId = "section-2",
            title = "COMO A VIDA ETERNA FOI PERDIDA",
            sectionNumber = 2,
        ),
        generatedAt = 123L,
    )

    @Test
    fun roundTripCompleto() {
        val decoded = PointReviewCodec.decode(PointReviewCodec.encode(report))
        assertEquals(report, decoded)
    }

    @Test
    fun preservaAcentosSeparadoresENovasLinhas() {
        val special = report.copy(
            title = "Título | com acentuação",
            reading = "Linha 1\nLinha 2 | com pipe",
            strengths = listOf("Força | com pipe", "Força\ncom nova linha"),
            improvements = listOf("Melhoria | com pipe"),
        )
        val decoded = PointReviewCodec.decode(PointReviewCodec.encode(special))
        assertEquals(special, decoded)
    }

    @Test
    fun payloadCorrompidoViraNull() {
        assertNull(PointReviewCodec.decode(""))
        assertNull(PointReviewCodec.decode("lixo"))
        assertNull(PointReviewCodec.decode("v1\nsectionId=x\npointNumber=2"))
    }

    @Test
    fun versaoDesconhecidaViraNull() {
        assertNull(PointReviewCodec.decode("v2\nsectionId=x"))
    }

    @Test
    fun camposAusentesSaoToleradosQuandoObrigatoriosExistem() {
        val raw = "v1\n" +
            "sectionId=section-2\n" +
            "pointNumber=2\n" +
            "title=Ponto\n" +
            "overall=OK\n" +
            "reading=Resumo\n" +
            "nextStep=OpenEditor|section-2|Ponto|2|Abrir no editor\n"
        val decoded = requireNotNull(PointReviewCodec.decode(raw))

        assertEquals(0, decoded.minutes)
        assertEquals(0L, decoded.generatedAt)
        assertTrue(decoded.strengths.isEmpty())
        assertTrue(decoded.improvements.isEmpty())
    }
}
