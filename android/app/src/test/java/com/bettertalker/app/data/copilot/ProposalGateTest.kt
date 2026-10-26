package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.ChatCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * “Sim/Não” no cartão de proposta: ação pendente, não roteamento genérico.
 */
class ProposalGateTest {

    private fun action(
        text: String,
        pending: Boolean = true,
        applied: Boolean = false,
        proposalMessageId: String? = "msg-summary",
        lastCopilotMessageId: String? = "msg-summary",
        proposalSectionId: String? = "sec-1",
        currentScopeId: String? = "sec-1",
    ) = proposalGateAction(
        text = text,
        hasPendingProposal = pending,
        applied = applied,
        proposalMessageId = proposalMessageId,
        lastCopilotMessageId = lastCopilotMessageId,
        proposalSectionId = proposalSectionId,
        currentScopeId = currentScopeId,
    )

    @Test
    fun proposalSimAcceptTest() {
        assertEquals(ProposalGateAction.ACCEPT, action("Sim"))
        assertEquals(ProposalGateAction.ACCEPT, action("pode"))
        assertEquals(ProposalGateAction.ACCEPT, action("manda"))
        assertEquals(ProposalGateAction.ACCEPT, action("claro"))
        assertEquals(ProposalGateAction.ACCEPT, action("isso"))
        assertEquals(ProposalGateAction.ACCEPT, action("ok"))
        assertEquals(ProposalGateAction.ACCEPT, action("confirmo"))
    }

    @Test
    fun proposalNaoRejectTest() {
        assertEquals(ProposalGateAction.REJECT, action("Não"))
        assertEquals(ProposalGateAction.REJECT, action("deixa"))
        assertEquals(ProposalGateAction.REJECT, action("cancela"))
        assertEquals(ProposalGateAction.REJECT, action("rejeito"))
        assertEquals(ProposalGateAction.REJECT, action("agora não"))
    }

    @Test
    fun proposalSimWithExtraTextTest() {
        assertEquals(ProposalGateAction.NONE, action("Sim, mas troque o exemplo"))
        assertEquals(ProposalGateAction.NONE, action("pode aplicar, só ajuste o tom"))
        assertEquals(ProposalGateAction.NONE, action("sim, por favor"))
    }

    @Test
    fun textQuestionSimTest() {
        // Nenhuma proposta pendente: o "Sim" continua no LLM.
        assertEquals(ProposalGateAction.NONE, action("Sim", pending = false))
    }

    @Test
    fun proposalNoDoubleApplyTest() {
        assertEquals(ProposalGateAction.NONE, action("Sim", applied = true))
    }

    @Test
    fun proposalStaleSimTest() {
        // A última fala do Copilot não é mais o summary: não executa às cegas.
        assertEquals(
            ProposalGateAction.NONE,
            action("Sim", lastCopilotMessageId = "msg-outra"),
        )
        assertEquals(
            ProposalGateAction.NONE,
            action("Sim", proposalMessageId = null),
        )
    }

    @Test
    fun proposalOtherScopeSimTest() {
        assertEquals(
            ProposalGateAction.OTHER_SCOPE,
            action("Sim", proposalSectionId = "sec-1", currentScopeId = "sec-2"),
        )
        // Rejeitar de outro escopo não aplica nada; é seguro.
        assertEquals(
            ProposalGateAction.REJECT,
            action("Não", proposalSectionId = "sec-1", currentScopeId = "sec-2"),
        )
    }

    @Test
    fun proposalSummaryHistoryTest() {
        val summary = proposalSummaryText(
            sectionTitle = "Cultivando o interesse",
            explanation = "reescrever o diálogo.",
        )
        assertEquals(
            "Proposta para a seção “Cultivando o interesse”: reescrever o diálogo. Quer que eu aplique?",
            summary,
        )
        val payload = ChatCodec.escMap(mapOf("text" to summary))
        assertEquals(summary, readableChatTurn("text", payload))
        assertEquals(
            "Proposta para a nota: aplicar o texto proposto. Quer que eu aplique?",
            proposalSummaryText(null, null),
        )
    }

    @Test
    fun shortAnswerClassifierNaoSequestraTextoLongo() {
        assertTrue(isShortProposalAnswer("Sim"))
        assertTrue(isShortProposalAnswer("sim."))
        assertTrue(isShortProposalAnswer("Não"))
        assertFalse(isShortProposalAnswer("Sim, mas..."))
        assertFalse(isShortProposalAnswer("o que você acha?"))
        assertTrue(proposalAnswerIsYes("claro"))
        assertTrue(proposalAnswerIsNo("dispensa"))
    }
}
