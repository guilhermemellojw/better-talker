package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointCoachingCodecTest {

    private val report = PointCoachingReport(
        sectionId = "section-2",
        pointNumber = 2,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = 4,
        overall = DiagnosisLevel.ATTENTION,
        reading = "Ponto 2 · 4 min · 1/2 subpontos",
        findings = listOf(
            PointCoachingFinding("Subpontos", DiagnosisLevel.ATTENTION, "1/2 com texto"),
            PointCoachingFinding("Fontes da nota", DiagnosisLevel.OK, "5/5 resolvidas"),
        ),
        steps = listOf(
            "Leia o ponto em voz alta.",
            "Desenvolva o subponto que falta.",
        ),
        nextStep = PointCoachingNextStep.OpenEditor(
            sectionId = "section-2",
            title = "COMO A VIDA ETERNA FOI PERDIDA",
            sectionNumber = 2,
        ),
        generatedAt = 123L,
    )

    @Test
    fun roundTripCompleto() {
        val decoded = PointCoachingCodec.decode(PointCoachingCodec.encode(report))

        assertEquals(report, decoded)
    }

    @Test
    fun preservaAcentosSeparadoresENovasLinhas() {
        val special = report.copy(
            title = "Título | com acentuação",
            reading = "Linha 1\nLinha 2 | com pipe",
            steps = listOf("Passo | com pipe", "Passo\ncom nova linha"),
            findings = listOf(
                PointCoachingFinding("Rótulo | especial", DiagnosisLevel.ATTENTION, "ação\ncom quebra"),
            ),
        )
        val decoded = PointCoachingCodec.decode(PointCoachingCodec.encode(special))

        assertEquals(special, decoded)
    }

    @Test
    fun payloadCorrompidoViraNull() {
        assertNull(PointCoachingCodec.decode(""))
        assertNull(PointCoachingCodec.decode("lixo"))
        assertNull(PointCoachingCodec.decode("v1\nsectionId=x\npointNumber=2"))
    }

    @Test
    fun versaoDesconhecidaViraNull() {
        assertNull(PointCoachingCodec.decode("v2\nsectionId=x"))
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
        val decoded = PointCoachingCodec.decode(raw)

        assertEquals(0, decoded?.minutes)
        assertEquals(0L, decoded?.generatedAt)
        assertTrue(decoded?.findings?.isEmpty() == true)
    }
}
