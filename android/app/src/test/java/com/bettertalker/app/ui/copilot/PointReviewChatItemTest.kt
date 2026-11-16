package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.DiagnosisLevel
import com.bettertalker.app.data.copilot.PointReviewCodec
import com.bettertalker.app.data.copilot.PointReviewNextStep
import com.bettertalker.app.data.copilot.PointReviewReport
import com.bettertalker.app.data.db.ChatEntity
import com.bettertalker.app.data.util.ChatCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointReviewChatItemTest {

    private val report = PointReviewReport(
        sectionId = "section-2",
        pointNumber = 2,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = 4,
        overall = DiagnosisLevel.ATTENTION,
        reading = "Ponto 2 · 1/2 subpontos",
        strengths = listOf("O ponto tem 2 subpontos."),
        improvements = listOf("Desenvolva o segundo subponto."),
        nextStep = PointReviewNextStep.OpenEditor(
            sectionId = "section-2",
            title = "COMO A VIDA ETERNA FOI PERDIDA",
            sectionNumber = 2,
        ),
        generatedAt = 7L,
    )

    private fun payload(reportRaw: String, text: String = "Resumo") =
        ChatCodec.escMap(mapOf("report" to reportRaw, "text" to text))

    @Test
    fun payloadValidoViraChatItemReview() {
        val item = pointReviewChatItem(
            id = "msg-1",
            fromMe = false,
            payload = payload(PointReviewCodec.encode(report)),
            sectionId = "section-2",
            origin = MessageOrigin.LOCAL,
            createdAt = 9L,
        )

        assertEquals("review", item.kind)
        assertEquals(report, item.review)
        assertEquals("Resumo", item.text)
        assertEquals(MessageOrigin.LOCAL, item.origin)
        assertEquals(9L, item.createdAt)
    }

    @Test
    fun payloadCorrompidoViraTextoComResumo() {
        val item = pointReviewChatItem(
            id = "msg-2",
            fromMe = false,
            payload = payload("lixo", text = "Resumo fallback"),
            createdAt = 1L,
        )

        assertEquals("text", item.kind)
        assertNull(item.review)
        assertEquals("Resumo fallback", item.text)
    }

    @Test
    fun payloadCorrompidoSemResumoMostraFallback() {
        val item = pointReviewChatItem(
            id = "msg-3",
            fromMe = false,
            payload = ChatCodec.escMap(mapOf("report" to "lixo")),
        )

        assertEquals("text", item.kind)
        assertNull(item.review)
        assertEquals("Revisão indisponível. Reanalise o ponto.", item.text)
    }

    @Test
    fun desativaApenasRevisoes() {
        val items = listOf(
            ChatEntity("m1", "note", false, "review", "{}", 1L),
            ChatEntity("m2", "note", false, "text", "{}", 2L),
            ChatEntity("m3", "note", false, "coaching", "{}", 3L),
            ChatEntity("m4", "note", false, "review", "{}", 4L),
        )

        assertEquals(listOf("m1", "m4"), reviewMessageIdsToDeactivate(items))
    }
}
