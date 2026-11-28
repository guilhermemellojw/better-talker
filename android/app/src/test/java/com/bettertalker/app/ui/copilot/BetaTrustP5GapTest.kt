package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ContentEligibilityGate
import com.bettertalker.app.data.copilot.DeliveryProvenance
import com.bettertalker.app.data.copilot.RecoveredDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-TRUST/P5 — 8 gap probes de D/P6, com o mesmo critério de reprovação.
 * Host-only: nenhuma chamada de LLM, rede, ADB ou dispositivo.
 */
class BetaTrustP5GapTest {

    private val jo17 = "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade."

    private fun corpus() = listOf(
        RecoveredDocument(
            sourceId = "nwt", reference = "João 17:17", text = jo17,
            documentUnit = "nwt", resolved = true, waived = false,
        ),
    )

    private fun source(rejected: Boolean = false) = DeliveryProvenance.newForMessage(
        messageId = "msg-1", turnId = "turn-1", noteId = "note-1",
        scopeId = "sec-1", targetKey = "SECTION:sec-1", corpus = listOf(jo17),
        questionRefs = emptyList(), content = jo17, boundAt = 1L, rejected = rejected,
    )

    @Test
    fun gapNonemptyOpenQuoteMustBeRejected() {
        val r = verifyGeneratedText(
            "A Bíblia diz: “Deus promete que nunca haverá guerra",
            documentarySources = corpus(),
        )
        assertEquals("", r.text)
        assertFalse(r.notice.isNullOrBlank())
        assertTrue(r.notice!!.contains("incompleto"))
        assertTrue(r.hasRemovals)
    }

    @Test
    fun gapNonemptyClosedInventedQuoteStillRemoved() {
        val r = verifyGeneratedText(
            "A Bíblia diz: “Deus promete que nunca haverá guerra”.",
            documentarySources = corpus(),
        )
        assertFalse(r.text.contains("Deus promete"))
        assertTrue(r.metrics.hasFlags)
    }

    @Test
    fun gapEmptyOpenQuoteMustBeRejectedWithNotice() {
        val r = verifyGeneratedText(
            "A Bíblia diz: “Deus promete que nunca haverá guerra",
        )
        assertEquals("", r.text)
        assertNotNull(r.notice)
    }

    @Test
    fun gapValidTnmCitationRemainsUsable() {
        val r = verifyGeneratedText(jo17, documentarySources = corpus())
        assertEquals(jo17, r.text)
        assertNull(r.notice)
    }

    @Test
    fun gapRewriteFromRejectedSourceReturnsNull() {
        assertNull(
            DeliveryProvenance.rewriteFrom(
                source = source(rejected = true), sourceText = jo17,
                revisedText = "revisão", messageId = "msg-r", turnId = null,
                noteId = "note-1", scopeId = "sec-1",
                targetKey = "SECTION:sec-1", boundAt = 2L,
            ),
        )
    }

    @Test
    fun gapSafeTextReturnsEmptyWhenBlocked() {
        val blocked = ContentEligibilityGate.safeText(
            "A Bíblia diz: “Deus promete que nunca haverá guerra”.",
            emptyList(), emptyList(),
        )
        assertEquals("", blocked)
        val good = ContentEligibilityGate.safeText(jo17, listOf(jo17), emptyList())
        assertTrue(good.contains("Santifica-os"))
    }

    @Test
    fun gapRejectedSourceCannotContinue() {
        assertNull(source(rejected = true).continueFrom(jo17, "$jo17 Aplicação.", 2L))
        assertNotNull(source().continueFrom(jo17, "$jo17 Aplicação.", 2L))
    }

    @Test
    fun gapConcurrentEditInvalidatesContinuation() {
        val bound = source()
        val payload = bound.toPayload().toMutableMap().apply { put("text", jo17) }
        assertNotNull(bound.stillValidAfterProvider(bound, payload, jo17))
        val edited = payload.toMutableMap().apply { put("text", "editado durante a chamada") }
        assertNull(bound.stillValidAfterProvider(bound, edited, jo17))
        val deleted = payload.toMutableMap().apply {
            put(DeliveryProvenance.KEY_SHA256, "0".repeat(64))
        }
        assertNull(bound.stillValidAfterProvider(bound, deleted, jo17))
    }
}
