package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #26-fix — turno que gera SÓ proposta (sem mensagem CP nova, ex.: refinement
 * da oratória) também conta/pílula; mensagem + proposta JUNTAS contam 1×
 * (uma decisão por evento); com followEnd, a proposta rola até o CARD (último
 * item — renderiza depois das mensagens).
 */
class ProposalOnlyPillTest {

    @Test
    fun nadaMudou_semProposta_nada() {
        assertEquals(
            MessagesEffect.Nothing,
            messagesEffect(
                messageAdded = false, proposalAdded = false, messageCount = 5,
                hasContext = false, hasTopicEmpty = false,
                lastItemIndex = 7, followEnd = false,
            ),
        )
    }

    @Test
    fun mensagemNormal_conta() {
        assertEquals(
            MessagesEffect.CountUnseen,
            messagesEffect(
                messageAdded = true, proposalAdded = false, messageCount = 6,
                hasContext = false, hasTopicEmpty = false,
                lastItemIndex = 7, followEnd = false,
            ),
        )
    }

    @Test
    fun propostaOnly_conta() {
        assertEquals(
            MessagesEffect.CountUnseen,
            messagesEffect(
                messageAdded = false, proposalAdded = true, messageCount = 5,
                hasContext = false, hasTopicEmpty = false,
                lastItemIndex = 7, followEnd = false,
            ),
        )
    }

    @Test
    fun mensagemEPropostaJuntas_contamUma() {
        // Uma chamada = uma decisão: mesmo com os dois chegando no MESMO
        // evento, o resultado é um único CountUnseen (não 2).
        val action = messagesEffect(
            messageAdded = true, proposalAdded = true, messageCount = 6,
            hasContext = false, hasTopicEmpty = false,
            lastItemIndex = 7, followEnd = false,
        )
        assertEquals(MessagesEffect.CountUnseen, action)
    }

    @Test
    fun propostaOnly_seguindoOFim_rolaAteOCard() {
        val action = messagesEffect(
            messageAdded = false, proposalAdded = true, messageCount = 5,
            hasContext = false, hasTopicEmpty = false,
            lastItemIndex = 7, followEnd = true,
        )
        assertTrue(action is MessagesEffect.ScrollTo)
        assertEquals(7, (action as MessagesEffect.ScrollTo).index)
    }
}
