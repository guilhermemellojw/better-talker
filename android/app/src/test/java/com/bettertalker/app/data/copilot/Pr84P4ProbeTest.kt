package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-REGRESSION/P4 — reusable independent probes (D).
 *
 * These are NOT part of PR #84. Copy this file into
 * android/app/src/test/java/com/bettertalker/app/data/copilot/ on the
 * candidate worktree, run the targeted test task, then remove it.
 *
 * On 2b476eeb: probe_final_open_quote and probe_card_empty_corpus are
 * expected to FAIL; the rest are expected PASS. On a fixed candidate all
 * must pass.
 */
class Pr84P4ProbeTest {

    private val jo17 = "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade."

    @Test
    fun probe_publication_edition_mismatch_must_block() {
        val d = SourceFidelityGate.enforce(
            "Veja w11 15/5 para detalhes.",
            listOf("w11 1/7 — Texto de outra edição."),
        )
        assertTrue("w11 15/5 não pode ser provado por w11 1/7", d.blocked)
    }

    @Test
    fun probe_final_open_quote_must_not_be_allowed() {
        val e = ContentEligibilityGate.forFinalAnswer(
            "A Bíblia diz: “Deus promete que nunca haverá guerra",
            listOf(jo17),
            emptyList(),
        )
        assertFalse("aspas abertas no texto final não podem ser elegíveis", e.allowed)
    }

    @Test
    fun probe_card_empty_corpus_must_not_fail_open() {
        val v = com.bettertalker.app.ui.copilot.verifyGeneratedText(
            text = "A Bíblia diz: \u201cDeus promete que nunca haverá guerra\u201d.",
            turnContext = null,
            extraSources = emptyList(),
        )
        assertFalse("card com corpus vazio não pode persistir quote", v.text.contains("Deus promete"))
    }

    @Test
    fun probe_short_quote_explicit_tnm_blocks() {
        val d = SourceFidelityGate.enforce(
            "Conforme a TNM: “Deus é amor”.",
            listOf("João 17:17 — Santifica-os por meio da verdade."),
        )
        assertTrue("quote curta atribuída à TNM não é termo", d.blocked)
    }

    @Test
    fun probe_range_needs_all_verses() {
        val d = SourceFidelityGate.enforce("João 17:1-11", listOf("João 17:1 — No princípio."))
        assertTrue("range 1-11 não passa só com o v. 1", d.blocked)
    }

    @Test
    fun probe_reassigned_provenance_rejected() {
        val source = DeliveryProvenance.newForMessage(
            messageId = "msg-source",
            turnId = "turn-1",
            noteId = "note-1",
            scopeId = "sec-1",
            targetKey = "SECTION:sec-1",
            corpus = listOf(jo17),
            questionRefs = emptyList(),
            content = jo17,
            boundAt = 1L,
        )
        assertNull(
            provenanceForMessage(mapOf("outra-mensagem" to source), "outra-mensagem", "note-1"),
        )
        assertNotNull(provenanceForMessage(mapOf("msg-source" to source), "msg-source", "note-1"))
    }

    @Test
    fun probe_legacy_fail_closed() {
        assertNull(DeliveryProvenance.fromPayload(mapOf("text" to jo17)))
        assertNull(provenanceForMessage(emptyMap(), "manual-1", "note-1"))
    }

    @Test
    fun probe_valid_content_all_pure_routes() {
        val routes = listOf(
            ContentEligibilityGate::forFinalAnswer,
            ContentEligibilityGate::forHistory,
            ContentEligibilityGate::forResponseCopy,
            ContentEligibilityGate::forCardCopy,
            ContentEligibilityGate::forCardInsert,
            ContentEligibilityGate::forTopicInsert,
            ContentEligibilityGate::forCancelledPartial,
            ContentEligibilityGate::forDraftReview,
        )
        routes.forEach { route ->
            val e = route("João 17:17 — “$jo17”", listOf(jo17), emptyList())
            assertTrue("conteúdo válido deve continuar utilizável", e.allowed)
        }
    }

    @Test
    fun probe_eight_routes_pure_contract() {
        val unsupported = "A Bíblia diz: “Deus promete que nunca haverá guerras nem tristeza”."
        val routes = listOf(
            ContentEligibilityGate::forFinalAnswer,
            ContentEligibilityGate::forHistory,
            ContentEligibilityGate::forResponseCopy,
            ContentEligibilityGate::forCardCopy,
            ContentEligibilityGate::forCardInsert,
            ContentEligibilityGate::forTopicInsert,
            ContentEligibilityGate::forCancelledPartial,
            ContentEligibilityGate::forDraftReview,
        )
        routes.forEach { route ->
            val e = route(unsupported, emptyList(), emptyList())
            assertFalse("rota pura não pode admitir quote sem fonte", e.allowed)
        }
    }

    @Test
    fun probe_rewrite_source_hash_must_match() {
        val source = DeliveryProvenance.newForMessage(
            messageId = "msg-source", turnId = "turn-1", noteId = "note-1",
            scopeId = "sec-1", targetKey = "SECTION:sec-1",
            corpus = listOf(jo17), questionRefs = emptyList(),
            content = jo17, boundAt = 1L,
        )
        assertNull(
            DeliveryProvenance.rewriteFrom(
                source = source,
                sourceText = jo17 + " adulterado",
                revisedText = jo17,
                messageId = "msg-rev", turnId = "turn-2", noteId = "note-1",
                scopeId = "sec-1", targetKey = "SECTION:sec-1", boundAt = 2L,
            ),
        )
    }
}
