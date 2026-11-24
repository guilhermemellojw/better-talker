package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Capabilities
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Context
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Message
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Outcome
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.TargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MENTOR-CONTINUITY-P6 — 14 cenários obrigatórios de recuperação segura. */
class ConversationalReferentRecoveryTest {

    private fun cardMsg(
        id: String,
        note: String = "n1",
        section: String? = "sec-1",
        status: CardStatus = CardStatus.AVAILABLE,
        createdAt: Long = 100L,
        targetId: String = "sec-1",
        kind: CardTargetKind = CardTargetKind.SECTION,
    ): Message {
        val card = CardPayload(
            targetKind = kind, targetId = targetId, targetLabel = "Ponto",
            baseHash = "h", status = status,
        )
        val payload = card.toMap().toMutableMap().apply {
            put("text", "<texto-do-card>Texto do card.</texto-do-card>")
        }
        return Message(id = id, noteId = note, sectionId = section, payload = payload, createdAt = createdAt)
    }

    private fun ctx(note: String = "n1", section: String? = "sec-1", exists: Boolean = true) = Context(
        noteId = note,
        sectionId = section,
        targets = mapOf(
            "SECTION:sec-1" to TargetState(exists),
            "SECTION:sec-2" to TargetState(exists),
            "NOTE:n1" to TargetState(exists),
            "NOTE:n2" to TargetState(exists),
        ),
    )

    private fun found(messages: List<Message>, context: Context): Outcome.Found {
        val r = ConversationalReferentRecovery.recover(messages, context)
        assertTrue("esperava Outcome.Found, veio $r", r is Outcome.Found)
        return r as Outcome.Found
    }

    // 1 — card válido da mesma nota e seção
    @Test
    fun t01_cardValidoMesmaNotaSecao() {
        val r = found(listOf(cardMsg("m1")), ctx())
        assertEquals("m1", r.messageId)
        assertTrue(r.capabilities.recoverableForConversation)
        assertTrue(r.capabilities.deepenable)
        assertTrue(r.capabilities.insertable)
        assertEquals(CardStatus.AVAILABLE, r.status)
    }

    // 2 — card de outra nota
    @Test
    fun t02_cardDeOutraNota_naoRecuperado() {
        assertEquals(Outcome.None, ConversationalReferentRecovery.recover(listOf(cardMsg("m1", note = "n2")), ctx(note = "n1")))
    }

    // 3 — card de outra seção
    @Test
    fun t03_cardDeOutraSecao_naoRecuperado() {
        assertEquals(Outcome.None, ConversationalReferentRecovery.recover(listOf(cardMsg("m1", section = "sec-2", targetId = "sec-2")), ctx(section = "sec-1")))
    }

    // 4 — IDs de seção iguais em notas distintas
    @Test
    fun t04_mesmoIdSecaoNotasDistintas_naoRecuperado() {
        val msgs = listOf(
            cardMsg("old", note = "n1", createdAt = 50L),
            cardMsg("new", note = "n2", section = "sec-1", createdAt = 100L),
        )
        assertEquals(Outcome.None, ConversationalReferentRecovery.recover(msgs, ctx(note = "n1", section = "sec-1")))
    }

    // 5 — escopo WHOLE
    @Test
    fun t05_escopoWhole_aceitaCardDeNota() {
        val r = found(
            listOf(cardMsg("m1", section = null, kind = CardTargetKind.NOTE, targetId = "n1")),
            ctx(section = null),
        )
        assertEquals("m1", r.messageId)
        assertEquals(null, r.sectionId)
    }

    @Test
    fun t05b_escopoWhole_rejeitaCardDeSecao() {
        assertEquals(Outcome.None, ConversationalReferentRecovery.recover(listOf(cardMsg("m1")), ctx(section = null)))
    }

    // 6 — payload inválido
    @Test
    fun t06_payloadInvalido_semReferente() {
        val m = Message("m1", "n1", "sec-1", mapOf("text" to "sem card"), 100L)
        assertEquals(Outcome.None, ConversationalReferentRecovery.recover(listOf(m), ctx()))
    }

    // 7 — card substituído
    @Test
    fun t07_cardSubstituido_usaOMaisRecente() {
        val msgs = listOf(
            cardMsg("older", createdAt = 50L),
            cardMsg("newer", createdAt = 150L),
        )
        val r = found(msgs, ctx())
        assertEquals("newer", r.messageId)
        assertEquals(CardStatus.AVAILABLE, r.status)
    }

    @Test
    fun t07b_cardRecenteRejeitado_naoFallbackParaAntigo() {
        val msgs = listOf(
            cardMsg("older", createdAt = 50L),
            cardMsg("newer", createdAt = 150L, status = CardStatus.DISMISSED),
        )
        assertEquals(Outcome.None, ConversationalReferentRecovery.recover(msgs, ctx()))
    }

    // 8 — card rejeitado
    @Test
    fun t08_cardRejeitado_naoPromovido() {
        assertEquals(
            Outcome.None,
            ConversationalReferentRecovery.recover(listOf(cardMsg("m1", status = CardStatus.DISMISSED)), ctx()),
        )
        assertEquals(
            Outcome.None,
            ConversationalReferentRecovery.recover(listOf(cardMsg("m1", status = CardStatus.FAILED)), ctx()),
        )
    }

    // 9 — card aceito como referente conversacional
    @Test
    fun t09_cardAceito_eReferenteValidoSemAutorizarInsercao() {
        val r = found(listOf(cardMsg("m1", status = CardStatus.APPLIED)), ctx())
        assertTrue(r.capabilities.recoverableForConversation)
        assertTrue(r.capabilities.deepenable)
        assertFalse("card já aplicado não autoriza inserção", r.capabilities.insertable)
    }

    @Test
    fun t09b_cardStale_eReferenteValido() {
        val r = found(listOf(cardMsg("m1", status = CardStatus.STALE)), ctx())
        assertTrue(r.capabilities.deepenable)
        assertTrue(r.capabilities.insertable) // contrato existente: STALE é inserível
    }

    // 10 — ausência de referente
    @Test
    fun t10_ausenciaDeReferente() {
        assertEquals(Outcome.None, ConversationalReferentRecovery.recover(emptyList(), ctx()))
    }

    // 11 — referente ambíguo (empate temporal)
    @Test
    fun t11_empateTemporal_ambiguo() {
        val msgs = listOf(
            cardMsg("a", createdAt = 100L, targetId = "sec-2"),
            cardMsg("b", createdAt = 100L, targetId = "sec-1"),
        )
        assertEquals(Outcome.Ambiguous, ConversationalReferentRecovery.recover(msgs, ctx()))
    }

    // 12 — ordenação determinística e empate
    @Test
    fun t12_ordenacaoDeterministica() {
        val msgs = listOf(
            cardMsg("c", createdAt = 300L),
            cardMsg("a", createdAt = 100L),
            cardMsg("b", createdAt = 200L),
        )
        assertEquals("c", found(msgs, ctx()).messageId)
        val tied = listOf(cardMsg("x", createdAt = 100L), cardMsg("y", createdAt = 100L))
        assertEquals(Outcome.Ambiguous, ConversationalReferentRecovery.recover(tied, ctx()))
        assertEquals(Outcome.Ambiguous, ConversationalReferentRecovery.recover(tied.reversed(), ctx()))
    }

    // 13 — recuperação a partir de histórico persistido (fixture sintética)
    @Test
    fun t13_fixtureHistoricoPersistido() {
        // Linhas sintéticas no formato do payload persistido (card + texto).
        val persisted = listOf(
            Message(
                id = "turn-1", noteId = "n1", sectionId = "sec-1",
                payload = mapOf(
                    "cardSchema" to "1", "cardTargetKind" to "SECTION",
                    "cardTargetId" to "sec-1", "cardTargetLabel" to "Ponto 1",
                    "cardStatus" to "AVAILABLE",
                    "text" to "<texto-do-card>Conteúdo do card.</texto-do-card>",
                ),
                createdAt = 1_700_000_000_000L,
            ),
            Message("turn-0", "n1", "sec-1", mapOf("text" to "pergunta"), 1_699_999_999_000L),
        )
        val r = found(persisted, ctx())
        assertEquals("turn-1", r.messageId)
        assertEquals("Conteúdo do card.", r.text)
    }

    // 14 — nenhuma autorização de inserção criada pela recuperação
    @Test
    fun t14_recuperacaoNaoAutorizaInsercao() {
        val before = listOf(cardMsg("m1", status = CardStatus.APPLIED))
        val snapshot = before.map { it.copy() }
        val r = found(before, ctx())
        assertEquals(snapshot, before) // componente não muta as mensagens
        assertFalse(r.capabilities.insertable)
        // Nenhuma API de inserção existe no Outcome — apenas capacidade informativa.
        assertEquals(true, r.capabilities.recoverableForConversation)
    }

    @Test
    fun t14b_targetNaoComprovado_naoRecuperado() {
        val m = cardMsg("m1")
        val context = Context(noteId = "n1", sectionId = "sec-1", targets = emptyMap())
        assertEquals(Outcome.None, ConversationalReferentRecovery.recover(listOf(m), context))
    }
}
