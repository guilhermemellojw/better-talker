package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.DiagnosisLevel
import com.bettertalker.app.data.copilot.DiagnosisNextStep
import com.bettertalker.app.data.copilot.DiagnosisS34Status
import com.bettertalker.app.data.copilot.OutlineDiagnosis
import com.bettertalker.app.data.copilot.OutlineDiagnosisCodec
import com.bettertalker.app.data.util.ChatCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineDiagnosisChatItemTest {

    private val report = OutlineDiagnosis(
        overall = DiagnosisLevel.ATTENTION,
        headline = "Esboço com pontos de atenção",
        summary = "Resumo do diagnóstico",
        s34Status = DiagnosisS34Status.LINKED,
        s34Detail = "S-34 de teste",
        criteria = emptyList(),
        points = emptyList(),
        priorities = listOf("Prioridade 1"),
        nextStep = DiagnosisNextStep.OpenNote("Abrir nota"),
        generatedAt = 7L,
    )

    private fun payload(reportRaw: String, text: String = "Resumo do diagnóstico") =
        ChatCodec.escMap(mapOf("report" to reportRaw, "text" to text))

    @Test
    fun payloadValidoViraChatItemDiagnosis() {
        val item = diagnosisChatItem(
            id = "msg-1",
            fromMe = false,
            payload = payload(OutlineDiagnosisCodec.encode(report)),
            sectionId = null,
            origin = MessageOrigin.LOCAL,
            createdAt = 99L,
        )

        assertEquals("diagnosis", item.kind)
        assertEquals(report, item.diagnosis)
        assertEquals("Resumo do diagnóstico", item.text)
        assertEquals(MessageOrigin.LOCAL, item.origin)
        assertEquals(99L, item.createdAt)
    }

    @Test
    fun payloadCorrompidoViraTextoComResumo() {
        val item = diagnosisChatItem(
            id = "msg-2",
            fromMe = false,
            payload = payload("lixo", text = "Resumo fallback"),
            createdAt = 1L,
        )

        assertEquals("text", item.kind)
        assertNull(item.diagnosis)
        assertEquals("Resumo fallback", item.text)
    }

    @Test
    fun payloadSemReportNaoQuebra() {
        val item = diagnosisChatItem(
            id = "msg-3",
            fromMe = false,
            payload = ChatCodec.escMap(mapOf("text" to "Só resumo")),
        )

        assertEquals("text", item.kind)
        assertNull(item.diagnosis)
        assertEquals("Só resumo", item.text)
    }

    @Test
    fun payloadCorrompidoSemResumoMostraFallback() {
        val item = diagnosisChatItem(
            id = "msg-5",
            fromMe = false,
            payload = ChatCodec.escMap(mapOf("report" to "lixo")),
        )

        assertEquals("text", item.kind)
        assertNull(item.diagnosis)
        assertEquals("Diagnóstico indisponível. Reanalise o esboço.", item.text)
    }

    @Test
    fun payloadPreservaResumoComAcentosESeparadores() {
        val item = diagnosisChatItem(
            id = "msg-4",
            fromMe = false,
            payload = payload(
                OutlineDiagnosisCodec.encode(report.copy(summary = "Resumo | com\nlinha")),
                text = "Resumo | com\nlinha",
            ),
        )

        assertEquals("diagnosis", item.kind)
        assertEquals("Resumo | com\nlinha", item.text)
        assertTrue(item.diagnosis!!.summary.contains("|"))
    }
}
