package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ContentEligibilityGate
import com.bettertalker.app.data.copilot.DeliveryProvenance
import com.bettertalker.app.data.copilot.SourceFidelityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-TRUST/P3 — fail-first das sondas de D e do fechamento das rotas reais.
 * Host-only: nenhum LLM, rede, Room, ADB ou dispositivo.
 */
class BetaTrustP3ClosureTest {

    private val tnm = "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade."

    private fun source(): DeliveryProvenance = DeliveryProvenance.newForMessage(
        messageId = "msg-source", turnId = "turn-1", noteId = "note-1",
        scopeId = "sec-1", targetKey = "SECTION:sec-1",
        corpus = listOf(tnm), questionRefs = listOf("jo1717"),
        content = tnm, boundAt = 1L,
    )

    @Test
    fun probeOpen_aspasAbertasSaoRemovidasNoResultadoFinal() {
        val text = "Texto fundamentado. A Bíblia diz: “trecho sem fechamento"
        val d = ContentEligibilityGate.forFinalAnswer(text, listOf(tnm), emptyList())
        assertFalse("aspas abertas não sobrevivem: ${d.text}", d.text.contains("“"))
        assertFalse(d.text.contains("sem fechamento"))
        assertTrue(d.text.contains("Texto fundamentado"))
    }

    @Test
    fun probeOpen_aspasLegitimasSaoPreservadas() {
        val text = "João 17:17 — “Santifica-os por meio da verdade; a tua palavra é a verdade.”"
        val d = ContentEligibilityGate.forFinalAnswer(text, listOf(tnm), emptyList())
        assertTrue(d.text.contains("Santifica-os"))
        assertTrue(d.allowed)
    }

    @Test
    fun probeEmpty_corpusVazioBloqueiaCitacaoLiteralMasNaoExplicacao() {
        val claim = "A Bíblia diz: “Deus promete que nunca haverá guerra”."
        val r = verifyGeneratedText(claim)
        assertFalse("corpus vazio não autoriza citação: ${r.text}", r.text.contains("nunca haverá guerra"))
        assertTrue(r.hasRemovals)

        val explanation = "Aqui va apresentar a ideia do ponto com calma."
        assertEquals(explanation, verifyGeneratedText(explanation).text)
    }

    @Test
    fun probeEmpty_cardBloqueiaCitacaoSemCorpus() {
        val d = ContentEligibilityGate.forCardInsert(
            "A Bíblia diz: “Deus promete que nunca haverá guerra”.", emptyList(), emptyList(),
        )
        assertFalse(d.allowed)
        assertFalse(d.text.contains("nunca haverá guerra"))
    }

    @Test
    fun revisaoSemFonteOuComEscopoDiverenteFalhaFechado() {
        val source = source()
        assertNull(DeliveryProvenance.rewriteFrom(
            source = source, sourceText = tnm, revisedText = "revisão",
            messageId = "msg-r", turnId = null, noteId = "note-1",
            scopeId = "sec-2", targetKey = "SECTION:sec-1", boundAt = 2L,
        ))
        assertNull(DeliveryProvenance.rewriteFrom(
            source = source, sourceText = tnm, revisedText = "revisão",
            messageId = "msg-r", turnId = null, noteId = "note-1",
            scopeId = "sec-1", targetKey = "SECTION:sec-2", boundAt = 2L,
        ))
        val allowed = DeliveryProvenance.rewriteFrom(
            source = source, sourceText = tnm, revisedText = "revisão",
            messageId = "msg-r", turnId = null, noteId = "note-1",
            scopeId = "sec-1", targetKey = "SECTION:sec-2", boundAt = 2L,
            explicitTargetChange = true,
        )
        assertNotNull(allowed)
    }

    @Test
    fun identidadeNotaEscopoAlvoEHashSaoVerificados() {
        val bound = source()
        assertTrue(bound.authorizesMessage("note-1", tnm, "sec-1"))
        assertFalse(bound.authorizesMessage("note-2", tnm, "sec-1"))
        assertFalse(bound.authorizesMessage("note-1", tnm, "sec-2"))
        assertFalse(bound.authorizesMessage("note-1", tnm + " alterado", "sec-1"))
        assertFalse(bound.authorizesCard("note-1", "SECTION:outra", tnm, "sec-1"))
        assertNull(bound.withTargetKey("SECTION:outra", authorized = false))
    }

    @Test
    fun restartComPayloadAdulteradoNaoRestauraAutorizacao() {
        val payload = source().toPayload().toMutableMap()
        val valid = DeliveryProvenance.fromPayload(payload)
        assertNotNull(valid)
        payload[DeliveryProvenance.KEY_SHA256] = "0".repeat(64)
        assertNull(DeliveryProvenance.fromPayload(payload))
    }

    @Test
    fun p3TextoInvalidoNaoViraInsercao() {
        val d = ContentEligibilityGate.forTopicInsert(
            "A Bíblia diz: “inventado sem fonte nenhuma”.", emptyList(), emptyList(),
        )
        assertFalse(d.allowed)
        assertFalse(d.text.contains("inventado"))
    }
}
