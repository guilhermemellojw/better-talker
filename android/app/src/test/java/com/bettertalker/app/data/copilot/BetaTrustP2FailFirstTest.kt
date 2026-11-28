package com.bettertalker.app.data.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-TRUST/P2 — sondas fail-first da coordenação.
 * Todas são puras: nenhuma rede, LLM, Room, ADB ou dispositivo.
 */
class BetaTrustP2FailFirstTest {

    private val tnmJo17 = "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade."

    @Test
    fun c03_quoteCurtaAtribuidaATnmNaoEhTermo() {
        val d = SourceFidelityGate.enforce(
            "Conforme a TNM: “Deus é amor”.",
            listOf("João 17:17 — Santifica-os por meio da verdade."),
        )
        assertTrue("citação curta atribuída à TNM não pode ser termo", d.blocked)
    }

    @Test
    fun c04_rangeCompletoExigeTodosOsVersiculos() {
        val d = SourceFidelityGate.enforce("João 17:1-11", listOf("João 17:1 — No princípio."))
        assertTrue("range 1-11 não pode ser provado só pelo versículo 1", d.blocked)
    }

    @Test
    fun c04_versiculo1NaoEhVersiculo10() {
        val d = SourceFidelityGate.enforce("João 17:1", listOf("João 17:10 — Outro texto."))
        assertTrue("versículo 1 não pode ser provado pelo 10", d.blocked)
    }

    @Test
    fun c05_textoDeOutraPassagemNaoAutorizaAtribuicao() {
        val d = SourceFidelityGate.enforce(
            "João 17:17 — “Santifica-os por meio da verdade; a tua palavra é a verdade.”",
            listOf("João 3:16 — Santifica-os por meio da verdade; a tua palavra é a verdade."),
        )
        assertTrue("texto de João 3 não prova atribuição a João 17", d.blocked)
    }

    @Test
    fun c06_concatenacaoArtificialNaoEhCitacaoValida() {
        val d = SourceFidelityGate.enforce(
            "A TNM diz: “Santifica-os por meio da verdade; a tua palavra é a verdade.”",
            listOf("João 17:17 — Santifica-os por meio da verdade.",
                   "João 17:17 — a tua palavra é a verdade."),
        )
        assertTrue("fragmentos de fontes distintas não formam citação", d.blocked)
    }

    @Test
    fun c07_aspasAbertasNoParcialSaoRemovidas() {
        val clean = SourceFidelityGate.sanitizePartialForDisplay(
            "A Bíblia ensina: “Deus promete paz sem fim", emptyList(), emptyList(),
        )
        assertFalse("parcial com aspas abertas não pode ser copiado", clean.contains("Deus promete"))
    }

    @Test
    fun c07_referenciaIncompletaNoParcialEBloqueada() {
        val clean = SourceFidelityGate.sanitizePartialForDisplay(
            "Veja João 17:", emptyList(), emptyList(),
        )
        assertFalse("referência incompleta não pode sobreviver", clean.contains("João 17"))
    }

    @Test
    fun c09_excecaoDePublicacaoNaoProvaProveniencia() {
        val d = SourceFidelityGate.enforce("Veja (be pág. 52) para detalhes.", emptyList())
        assertTrue("publicação citada sem texto recuperado não pode ser aceita", d.blocked)
    }

    @Test
    fun c01_c02_provenienciaPersistidaPorMensagemNotaEConteudo() {
        val text = "João 17:17 — “Santifica-os por meio da verdade.”"
        val bound = DeliveryProvenance.newForMessage(
            messageId = "msg-1", turnId = "turn-1", noteId = "note-1",
            scopeId = "sec-1", targetKey = "SECTION:sec-1",
            corpus = listOf("João 17:17 — Santifica-os por meio da verdade."),
            questionRefs = emptyList(), content = text,
            boundAt = 1_700_000_000_000L,
        )
        val restored = DeliveryProvenance.fromPayload(bound.toPayload())
        assertTrue(restored != null)
        assertTrue(restored!!.binds(text))
        assertTrue(provenanceForMessage(mapOf("msg-1" to restored), "msg-1", "note-1") == restored)
        assertTrue(provenanceForMessage(mapOf("msg-1" to restored), "msg-1", "note-2") == null)
        assertTrue(restored.matches("note-1", "SECTION:sec-1"))
        assertFalse(restored.matches("note-1", "SECTION:sec-2"))
        assertFalse(restored.binds(text + " adulterado"))
        // Corpus de outro turno não pode ser usado para validar esta mensagem.
        val wrongCorpus = listOf("João 3:16 — Santifica-os por meio da verdade.")
        assertFalse(ContentEligibilityGate.forHistory(
            text, wrongCorpus, emptyList(),
        ).allowed)
        assertTrue(ContentEligibilityGate.forHistory(
            text, restored.corpus, emptyList(),
        ).allowed)
    }

    @Test
    fun c03_tnmExplicitaComTextoCorretoContinuaValida() {
        val d = SourceFidelityGate.enforce(
            "Conforme a TNM: “${"Santifica-os por meio da verdade; a tua palavra é a verdade."}”",
            listOf(tnmJo17),
        )
        assertFalse("texto TNM efetivamente recuperado deve permanecer", d.blocked)
        assertTrue(d.text.contains("Santifica-os"))
    }
}
