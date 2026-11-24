package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Message
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.TargetState
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.MentorContinuityRouting.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MENTOR-INTEGRATION-P9 — regressões de integração (contratos puros). */
class MentorContinuityRoutingTest {

    private fun cardMsg(
        id: String = "m1",
        note: String = "n1",
        section: String? = "sec-1",
        targetId: String = "sec-1",
        kind: CardTargetKind = CardTargetKind.SECTION,
        status: CardStatus = CardStatus.AVAILABLE,
        createdAt: Long = 100L,
        label: String = "Romanos 8",
    ): Message {
        val payload = CardPayload(
            targetKind = kind, targetId = targetId, targetLabel = label,
            baseHash = "h", status = status,
        ).toMap().toMutableMap().apply {
            put("text", "<texto-do-card>Texto do card.</texto-do-card>")
        }
        return Message(id, note, section, payload, createdAt)
    }

    private fun suggestion(label: String = "Desenvolva o ponto 1") = NextStepSuggestion(
        label = label,
        detail = "Abra o ponto e escreva o texto.",
        action = ResolvedChatNav.DevelopCurrent("sec-1", "Ponto 1"),
    )

    private fun targets(exists: Boolean = true) = mapOf(
        "SECTION:sec-1" to TargetState(exists),
        "SECTION:sec-2" to TargetState(exists),
        "NOTE:n1" to TargetState(exists),
        "NOTE:n2" to TargetState(exists),
    )

    private fun plan(
        request: String = "Explique melhor",
        note: String = "n1",
        scope: String? = "sec-1",
        focus: Focus = Focus.SECTION,
        messages: List<Message> = listOf(cardMsg()),
        sugg: NextStepSuggestion? = null,
        suggNote: String? = null,
        suggScope: String? = null,
        lastResponse: String? = null,
    ) = MentorContinuityRouting.plan(
        request = request, noteId = note, scope = scope, focus = focus,
        messages = messages, targets = targets(),
        suggestion = sugg, suggestionNoteId = suggNote, suggestionSectionId = suggScope,
        lastResponse = lastResponse,
        sectionTitles = mapOf("sec-1" to "Ponto 1", "sec-2" to "Ponto 2"),
    )

    // 1 — aprofundamento de card válido
    @Test
    fun t01_cardValido_aprofundaComTopicoDoCard() {
        val p = plan()
        assertTrue(p.action is Action.DeepenCard)
        assertEquals("Romanos 8", (p.action as Action.DeepenCard).topic)
        assertTrue(p.capabilities!!.recoverableForConversation)
        assertTrue(p.capabilities!!.deepenable)
    }

    // 2 — retomada após reinício (sem sugestão volátil; card persistido)
    @Test
    fun t02_retomadaAposReinicio_recuperaCardPersistido() {
        val p = plan(request = "Vamos continuar de onde paramos", sugg = null)
        assertTrue(p.action is Action.DeepenCard)
        assertEquals("Romanos 8", (p.action as Action.DeepenCard).topic)
    }

    // 3 — troca de nota
    @Test
    fun t03_trocaDeNota_naoUsaCardAntigo() {
        val p = plan(note = "n2", messages = listOf(cardMsg(note = "n1")))
        assertFalse("card de outra nota não deve ser aprofundado", p.action is Action.DeepenCard)
        assertTrue(p.action is Action.ExplainActiveSection)
        assertEquals("sec-1", (p.action as Action.ExplainActiveSection).sectionId)
    }

    // 4 — troca de seção
    @Test
    fun t04_trocaDeSecao_explicaTopicoAtivo() {
        val p = plan(scope = "sec-2", messages = listOf(cardMsg(section = "sec-1")))
        assertTrue(p.action is Action.ExplainActiveSection)
        assertEquals("Ponto 2", (p.action as Action.ExplainActiveSection).title)
    }

    // 5 — card aceito sem reinserção
    @Test
    fun t05_cardAceito_recuperavelSemAutorizarInsercao() {
        val p = plan(messages = listOf(cardMsg(status = CardStatus.APPLIED)))
        assertTrue(p.action is Action.DeepenCard)
        assertFalse("APPLIED não autoriza inserção", p.capabilities!!.insertable)
        assertTrue(p.capabilities!!.deepenable)
    }

    // 6 — referente inválido ou ambíguo
    @Test
    fun t06_ambiguidadeTemporal_pedeEsclarecimento() {
        val p = plan(messages = listOf(cardMsg("a", createdAt = 100L), cardMsg("b", createdAt = 100L)))
        assertTrue(p.action is Action.Clarify)
    }

    @Test
    fun t06b_payloadInvalido_semReferenteSeguro() {
        val bad = Message("x", "n1", "sec-1", mapOf("text" to "sem card"), 100L)
        val p = plan(messages = listOf(bad), scope = null, focus = Focus.WHOLE)
        assertTrue(p.action is Action.Clarify)
    }

    // 7 — sugestão com origem incompatível
    @Test
    fun t07_sugestaoComOrigemIncompativel_naoEExplicada() {
        val p = plan(messages = emptyList(), sugg = suggestion(), suggNote = "n1", suggScope = "sec-2")
        assertFalse(p.action is Action.ExplainSuggestion)
    }

    @Test
    fun t07b_sugestaoValida_explicada() {
        val p = plan(
            messages = emptyList(), sugg = suggestion(),
            suggNote = "n1", suggScope = "sec-1",
        )
        assertTrue(p.action is Action.ExplainSuggestion)
        assertEquals("Abra o ponto e escreva o texto.", (p.action as Action.ExplainSuggestion).detail)
    }

    // 8 — pedido explícito de redação
    @Test
    fun t08_pedidoExplicitoRedacao_prossegueFluxoNormal() {
        assertEquals(Action.ProceedNormal, plan(request = "Desenvolva o ponto 1").action)
        assertEquals(Action.ProceedNormal, plan(request = "Redija o texto").action)
    }

    // 9 — preservação de memória e autoria
    @Test
    fun t09_memoriaNaoViraTopicoDoAprofundamento() {
        val p = plan(lastResponse = " decisão confirmada pelo usuário sobre outro tema ")
        assertEquals("Romanos 8", (p.action as Action.DeepenCard).topic)
        // A memória canônica permanece fora da ação: o tópico vem só do card.
        assertFalse((p.action as Action.DeepenCard).topic.contains("decisão"))
    }

    // 10 — ausência de promoção factual
    @Test
    fun t10_historicoNaoEhPromovidoAEvidencia() {
        val p = plan(
            messages = emptyList(), scope = null, focus = Focus.WHOLE,
            lastResponse = "João 3:16 diz que Deus amou o mundo",
        )
        assertTrue(p.action is Action.Clarify)
        assertFalse((p.action as Action.Clarify).reason.contains("João"))
    }

    @Test
    fun t10b_flashSemScope_preservaFluxoNormal() {
        assertEquals(Action.ProceedNormal, plan(request = "Oi", messages = emptyList()).action)
    }


    // ---- P10 — normalização APPLIED sem efeito observável ----

    @Test
    fun b01_applied_actionPreservaStatusEPayloadOriginal() {
        val msg = cardMsg(status = CardStatus.APPLIED)
        val snapshot = msg.payload.toMap()
        val p = plan(messages = listOf(msg))
        val action = p.action as Action.DeepenCard
        assertEquals(CardStatus.APPLIED, action.card.status)
        assertEquals("APPLIED", action.card.toMap()["cardStatus"])
        assertEquals(snapshot, msg.payload) // payload persistido não foi modificado
        assertFalse("APPLIED não recupera autorização de inserção", p.capabilities!!.insertable)
        assertTrue(p.capabilities!!.deepenable)
    }

    @Test
    fun b02_applied_topicoVemDoCardNaoDaMemoria() {
        val p = plan(
            messages = listOf(cardMsg(status = CardStatus.APPLIED)),
            lastResponse = "decisão confirmada pelo usuário sobre outro tema",
        )
        val topic = (p.action as Action.DeepenCard).topic
        assertEquals("Romanos 8", topic)
        assertFalse(topic.contains("decisão"))
        assertEquals(CardStatus.APPLIED, (p.action as Action.DeepenCard).card.status)
    }


    // ---- P12 / F5 — precedência da intenção sobre ambiguidade ----

    private fun ambiguous() = listOf(
        cardMsg(id = "a", createdAt = 100L),
        cardMsg(id = "b", createdAt = 100L),
    )

    @Test
    fun f5_ambiguidadeComSaudacao_naoSeqestra() {
        assertEquals(Action.ProceedNormal, plan(request = "Oi", messages = ambiguous()).action)
        assertEquals(Action.ProceedNormal, plan(request = "Bom dia", messages = ambiguous()).action)
    }

    @Test
    fun f5_ambiguidadeComRedacaoExplicita_preservada() {
        assertEquals(Action.ProceedNormal, plan(request = "Desenvolva o ponto 1", messages = ambiguous()).action)
    }

    @Test
    fun f5_ambiguidadeComConsentimento_preservada() {
        assertEquals(Action.ProceedNormal, plan(request = "Sim", messages = ambiguous()).action)
    }

    @Test
    fun f5_cardSemCorpoUtilizavel_naoAprofunda() {
        val base = cardMsg()
        val semCorpo = base.copy(payload = base.payload.toMutableMap().apply { put("text", "") })
        assertTrue(plan(messages = listOf(semCorpo)).action is Action.Clarify)
    }
}
