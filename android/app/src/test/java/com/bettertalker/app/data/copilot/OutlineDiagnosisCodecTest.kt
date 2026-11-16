package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.ChatCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutlineDiagnosisCodecTest {

    private fun report(
        nextStep: DiagnosisNextStep = DiagnosisNextStep.OpenSection(
            sectionId = "sec-1",
            title = "Título | 1",
            sectionNumber = 1,
            label = "Abrir | ponto",
        ),
    ) = OutlineDiagnosis(
        overall = DiagnosisLevel.ATTENTION,
        headline = "Atenção | linha\nnova",
        summary = "Resumo\tcom tab e acentuação çã",
        s34Status = DiagnosisS34Status.LINKED,
        s34Detail = "S-34 | detalhe",
        criteria = listOf(
            DiagnosisCriterion(
                id = DiagnosisCriterionId.STRUCTURE,
                label = "Estrutura | título",
                level = DiagnosisLevel.ATTENTION,
                finding = "Achado\ncom linha",
                action = "Ação | x",
            ),
            DiagnosisCriterion(
                id = DiagnosisCriterionId.SOURCES,
                label = "Fontes",
                level = DiagnosisLevel.OK,
                finding = "3/3 resolvidas",
            ),
        ),
        points = listOf(
            DiagnosisPoint(
                sectionId = "sec-1",
                number = 1,
                title = "Título | 1",
                minutes = 5,
                level = DiagnosisLevel.OK,
                finding = "1/1 | ok",
                actionLabel = "Abrir",
            ),
            DiagnosisPoint(
                sectionId = "sec-2",
                number = 2,
                title = "Título 2",
                minutes = 0,
                level = DiagnosisLevel.ATTENTION,
                finding = "sem texto",
                actionLabel = "Desenvolver",
            ),
        ),
        priorities = listOf("Prioridade | 1", "Prioridade 2"),
        nextStep = nextStep,
        generatedAt = 42L,
    )

    @Test
    fun roundTripComAcentosSeparadoresENovasLinhas() {
        val original = report()
        val decoded = OutlineDiagnosisCodec.decode(OutlineDiagnosisCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripDeTodosOsNextSteps() {
        val steps = listOf(
            DiagnosisNextStep.OpenNote(),
            DiagnosisNextStep.ShowSources("Acompanhar indexação"),
            DiagnosisNextStep.LinkS34(),
            DiagnosisNextStep.ReimportS34(),
            DiagnosisNextStep.Reanalyze(),
        )
        steps.forEach { step ->
            val original = report(step)
            assertEquals(original, OutlineDiagnosisCodec.decode(OutlineDiagnosisCodec.encode(original)))
        }
    }

    @Test
    fun payloadExtraiDiagnostico() {
        val original = report()
        val payload = ChatCodec.escMap(
            mapOf(
                "report" to OutlineDiagnosisCodec.encode(original),
                "text" to original.summary,
            ),
        )
        assertEquals(original, diagnosisFromPayload(payload))
    }

    @Test
    fun payloadCorrompidoRetornaNull() {
        assertNull(diagnosisFromPayload(ChatCodec.escMap(mapOf("report" to "lixo"))))
        assertNull(diagnosisFromPayload(ChatCodec.escMap(mapOf("text" to "só resumo"))))
        assertNull(OutlineDiagnosisCodec.decode(""))
        assertNull(OutlineDiagnosisCodec.decode("v2\noverall=OK"))
        assertNull(OutlineDiagnosisCodec.decode("v1\noverall=OK"))
    }

    @Test
    fun campoDesconhecidoNaoQuebraODecode() {
        val encoded = OutlineDiagnosisCodec.encode(report()) +
            "\ncampoNovo=qualquer coisa"
        assertEquals(report(), OutlineDiagnosisCodec.decode(encoded))
    }
}
