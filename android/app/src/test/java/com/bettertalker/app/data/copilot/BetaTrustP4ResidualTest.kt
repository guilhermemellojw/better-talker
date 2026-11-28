package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BETA-TRUST/P4 — fechamento residual P1-B e P2. Host-only, sem LLM/ADB. */
class BetaTrustP4ResidualTest {

    private val jo17 = "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade."

    private fun source(rejected: Boolean = false) = DeliveryProvenance.newForMessage(
        messageId = "msg-1", turnId = "turn-1", noteId = "note-1",
        scopeId = "sec-1", targetKey = "SECTION:sec-1", corpus = listOf(jo17),
        questionRefs = emptyList(), content = jo17, boundAt = 1L, rejected = rejected,
    )

    @Test
    fun p1b_openQuoteIsRejectedWithFindingAndNotice() {
        val e = ContentEligibilityGate.forFinalAnswer(
            "A Bíblia diz: “Deus promete que nunca haverá guerra", listOf(jo17), emptyList(),
        )
        assertFalse(e.allowed)
        assertTrue(e.findings.contains(FidelityFinding.UNFINISHED_REDACTED))
        assertNotNull(e.notice)
        assertTrue(e.notice!!.contains("incompleto"))
    }

    @Test
    fun p1b_validLiteralAndParaphraseRemainUsable() {
        val literal = ContentEligibilityGate.forFinalAnswer(jo17, listOf(jo17), emptyList())
        assertTrue(literal.allowed)
        assertTrue(literal.text.contains("Santifica-os"))
        val paraphrase = ContentEligibilityGate.forFinalAnswer(
            "Em outras palavras, a ideia é confiar.",
            listOf(jo17), emptyList(),
        )
        assertTrue(paraphrase.allowed)
    }

    @Test
    fun p1b_rejectedProvenanceBlocksCopyInsertAndHistory() {
        val bound = source(rejected = true)
        assertFalse(bound.authorizesMessage("note-1", jo17, "sec-1"))
        assertFalse(bound.authorizesCard("note-1", "SECTION:sec-1", jo17, "sec-1"))
        assertNull(bound.withTargetKey("SECTION:outra", authorized = true))
    }

    @Test
    fun p1b_rejectedFlagSurvivesPayloadRoundtrip() {
        val restored = DeliveryProvenance.fromPayload(source(rejected = true).toPayload())
        assertNotNull(restored)
        assertTrue(restored!!.rejected)
        assertFalse(restored.authorizesMessage("note-1", jo17, "sec-1"))
    }

    @Test
    fun p24_continuationKeepsIdentityAndRehashes() {
        val bound = source()
        val merged = "$jo17 Aplicação breve."
        val continued = bound.continueFrom(jo17, merged, 2L)
        assertNotNull(continued)
        assertEquals("msg-1", continued!!.messageId)
        assertEquals("note-1", continued.noteId)
        assertEquals("sec-1", continued.scopeId)
        assertEquals(bound.corpus, continued.corpus)
        assertEquals(bound.questionRefs, continued.questionRefs)
        assertTrue(continued.binds(merged))
        assertFalse(continued.binds(jo17))
        assertNull(bound.continueFrom(jo17 + " adulterado", merged, 2L))
    }

    @Test
    fun p24_continuationWithoutGroundingIsBlocked() {
        val invalid = ContentEligibilityGate.forFinalAnswer(
            "A Bíblia diz: “inventado sem fonte nenhuma”.", emptyList(), emptyList(),
        )
        assertFalse(invalid.allowed)
        assertTrue(invalid.findings.contains(FidelityFinding.LITERAL_UNSUPPORTED))
    }

    @Test
    fun p21_topicInsertRouteUsesItsOwnContract() {
        val e = ContentEligibilityGate.forTopicInsert(
            "João 17:17 — “Santifica-os por meio da verdade; a tua palavra é a verdade.”",
            listOf(jo17), emptyList(),
        )
        assertTrue(e.allowed)
        val bad = ContentEligibilityGate.forTopicInsert(
            "A Bíblia diz: “inventado sem fonte nenhuma”.", emptyList(), emptyList(),
        )
        assertFalse(bad.allowed)
    }
}
