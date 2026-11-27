package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-TRUST/P2 — proveniência persistida, sem Room/Rede/LLM.
 *
 * O payload da ChatEntity é a fonte de verdade; o mapa em memória é apenas
 * cache. Estes testes cobrem o contrato que AI-09 usará no handoff.
 */
class DeliveryProvenanceTest {

    private val sourceText =
        "João 17:17 — “Santifica-os por meio da verdade; a tua palavra é a verdade.”"
    private val sourceCorpus = listOf(
        "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade.",
    )

    private fun source(): DeliveryProvenance = DeliveryProvenance.newForMessage(
        messageId = "msg-source",
        turnId = "turn-1",
        noteId = "note-1",
        scopeId = "sec-1",
        targetKey = "SECTION:sec-1",
        corpus = sourceCorpus,
        questionRefs = emptyList(),
        content = sourceText,
        boundAt = 1_700_000_000_000L,
    )

    @Test
    fun cardValidoAplicavelDepoisDoTurnoERestart() {
        val source = source()
        val payload = com.bettertalker.app.data.util.ChatCodec.unescMap(
            com.bettertalker.app.data.util.ChatCodec.escMap(
                mapOf("text" to sourceText) + source.toPayload(),
            ),
        )
        // Simula o fim do turno: nenhum activeDeliveryCorpus/QuestionRefs.
        val restored = DeliveryProvenance.fromPayload(payload)
        assertNotNull(restored)
        assertTrue(restored!!.binds(sourceText))
        assertEquals(source.corpus, restored.corpus)
        val gate = ContentEligibilityGate.forCardInsert(
            sourceText, restored.corpus, restored.questionRefs,
        )
        assertTrue(gate.allowed)
    }

    @Test
    fun cardDeOutroTurnoNotaOuAlvoNaoHerdaCorpus() {
        val source = source()
        assertTrue(source.matches("note-1", "SECTION:sec-1"))
        assertFalse(source.matches("note-2", "SECTION:sec-1"))
        assertFalse(source.matches("note-1", "SECTION:sec-2"))
        assertNull(provenanceForMessage(mapOf("msg-source" to source), "msg-source", "note-2"))
        val wrongCorpus = listOf("João 3:16 — “Santifica-os por meio da verdade.”")
        assertFalse(ContentEligibilityGate.forCardInsert(
            sourceText, wrongCorpus, emptyList(),
        ).allowed)
    }

    @Test
    fun cardAdulteradoEhRejeitado() {
        val source = source()
        val tampered = source.toPayload().toMutableMap().apply {
            put(DeliveryProvenance.KEY, get(DeliveryProvenance.KEY)!!.replace("João 17:17", "João 3:16"))
        }
        assertNull(DeliveryProvenance.fromPayload(tampered))
        assertFalse(source.binds(sourceText.replace("17:17", "3:16")))
        // Conteúdo do card alterado sem nova proveniência não passa no gate.
        assertFalse(ContentEligibilityGate.forCardInsert(
            sourceText.replace("17:17", "3:16"), source.corpus, emptyList(),
        ).allowed)
    }

    @Test
    fun revisaoUsaCorpusDaFonteENaoOTextoRevisadoComoSuaFonte() {
        val source = source()
        val revised = "João 17:17 — “Santifica-os por meio da verdade; a tua palavra é a verdade.” " +
            "Aplicação breve."
        val rewritten = DeliveryProvenance.rewriteFrom(
            source = source,
            sourceText = sourceText,
            revisedText = revised,
            messageId = "msg-rewrite",
            turnId = "turn-2",
            noteId = "note-1",
            scopeId = "sec-1",
            targetKey = "SECTION:sec-1",
            boundAt = 1_700_000_100_000L,
        )
        assertNotNull(rewritten)
        assertEquals(source.corpus, rewritten!!.corpus)
        assertEquals("msg-source", rewritten.sourceMessageId)
        assertTrue(rewritten.binds(revised))
        assertFalse(rewritten.binds(sourceText))
        assertFalse(rewritten.corpus.contains(revised))
        val restored = DeliveryProvenance.fromPayload(rewritten.toPayload())
        assertNotNull(restored)
        assertTrue(ContentEligibilityGate.forCardInsert(
            revised, restored!!.corpus, restored.questionRefs,
        ).allowed)
        // Uma ref nova no texto revisado não pode ser autorizada pela fonte.
        assertFalse(ContentEligibilityGate.forCardInsert(
            revised + " Mateus 24:14.", restored.corpus, restored.questionRefs,
        ).allowed)
    }

    @Test
    fun revisaoComTextoFonteDivergenteNaoCriaProveniencia() {
        assertNull(DeliveryProvenance.rewriteFrom(
            source = source(),
            sourceText = sourceText + " adulterado",
            revisedText = sourceText,
            messageId = "msg-x",
            turnId = null,
            noteId = "note-1",
            scopeId = "sec-1",
            targetKey = "SECTION:sec-1",
            boundAt = 1L,
        ))
        assertNull(DeliveryProvenance.rewriteFrom(
            source = source(),
            sourceText = sourceText,
            revisedText = " ",
            messageId = "msg-x",
            turnId = null,
            noteId = "note-1",
            scopeId = "sec-1",
            targetKey = "SECTION:sec-1",
            boundAt = 1L,
        ))
    }

    @Test
    fun pickDeAlvoPreservaCorpusEConteudoMasRebind() {
        val source = source()
        val rebound = source.withTargetKey("SECTION:sec-2")
        assertTrue(rebound.binds(sourceText))
        assertEquals(source.corpus, rebound.corpus)
        assertTrue(rebound.matches("note-1", "SECTION:sec-2"))
        assertFalse(rebound.matches("note-1", "SECTION:sec-1"))
        assertEquals("msg-source", DeliveryProvenance.fromPayload(rebound.toPayload())!!.messageId)
    }

    @Test
    fun payloadAusenteOuSemDigestFalhaFechado() {
        assertNull(DeliveryProvenance.fromPayload(emptyMap()))
        val payload = source().toPayload().toMutableMap()
        payload.remove(DeliveryProvenance.KEY_SHA256)
        assertNull(DeliveryProvenance.fromPayload(payload))
        val wrong = source().toPayload().toMutableMap().apply {
            put(DeliveryProvenance.KEY_SHA256, "0".repeat(64))
        }
        assertNull(DeliveryProvenance.fromPayload(wrong))
    }

    @Test
    fun autoriaManualELegadoSemProvenienciaFalhamFechado() {
        assertNull(provenanceForMessage(emptyMap(), "manual-1", "note-1"))
        assertNull(DeliveryProvenance.fromPayload(mapOf("text" to sourceText)))
    }
}
