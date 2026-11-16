package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.DiagnosisLevel
import com.bettertalker.app.data.copilot.PointCoachingCodec
import com.bettertalker.app.data.copilot.PointCoachingFinding
import com.bettertalker.app.data.copilot.PointCoachingNextStep
import com.bettertalker.app.data.copilot.PointCoachingReport
import com.bettertalker.app.data.db.ChatEntity
import com.bettertalker.app.data.util.ChatCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointCoachingChatItemTest {

    private val report = PointCoachingReport(
        sectionId = "section-2",
        pointNumber = 2,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = 4,
        overall = DiagnosisLevel.ATTENTION,
        reading = "Ponto 2 · 1/2 subpontos",
        findings = listOf(
            PointCoachingFinding("Subpontos", DiagnosisLevel.ATTENTION, "1/2 com texto"),
        ),
        steps = listOf("Desenvolva o primeiro subponto."),
        nextStep = PointCoachingNextStep.OpenEditor(
            sectionId = "section-2",
            title = "COMO A VIDA ETERNA FOI PERDIDA",
            sectionNumber = 2,
        ),
        generatedAt = 7L,
    )

    private fun payload(reportRaw: String, text: String = "Resumo") =
        ChatCodec.escMap(mapOf("report" to reportRaw, "text" to text))

    @Test
    fun payloadValidoViraChatItemCoaching() {
        val item = pointCoachingChatItem(
            id = "msg-1",
            fromMe = false,
            payload = payload(PointCoachingCodec.encode(report)),
            sectionId = "section-2",
            origin = MessageOrigin.LOCAL,
            createdAt = 9L,
        )

        assertEquals("coaching", item.kind)
        assertEquals(report, item.coaching)
        assertEquals("Resumo", item.text)
        assertEquals(MessageOrigin.LOCAL, item.origin)
        assertEquals(9L, item.createdAt)
    }

    @Test
    fun payloadCorrompidoViraTextoComResumo() {
        val item = pointCoachingChatItem(
            id = "msg-2",
            fromMe = false,
            payload = payload("lixo", text = "Resumo fallback"),
            createdAt = 1L,
        )

        assertEquals("text", item.kind)
        assertNull(item.coaching)
        assertEquals("Resumo fallback", item.text)
    }

    @Test
    fun payloadCorrompidoSemResumoMostraFallback() {
        val item = pointCoachingChatItem(
            id = "msg-3",
            fromMe = false,
            payload = ChatCodec.escMap(mapOf("report" to "lixo")),
        )

        assertEquals("text", item.kind)
        assertNull(item.coaching)
        assertEquals("Mentoria indisponível. Reanalise o ponto.", item.text)
    }

    @Test
    fun desativaApenasCoachings() {
        val items = listOf(
            ChatEntity("m1", "note", false, "coaching", "{}", 1L),
            ChatEntity("m2", "note", false, "text", "{}", 2L),
            ChatEntity("m3", "note", false, "diagnosis", "{}", 3L),
            ChatEntity("m4", "note", false, "coaching", "{}", 4L),
        )

        assertEquals(listOf("m1", "m4"), coachingMessageIdsToDeactivate(items))
    }
}
