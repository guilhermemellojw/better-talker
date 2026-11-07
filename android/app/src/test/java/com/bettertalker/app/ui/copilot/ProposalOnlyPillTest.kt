package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(action.instant)
    }

    // ---------- #fix-scroll: primeira carga × eventos seguintes ----------

    @Test
    fun primeiraCarga_posicionaInstantaneoNoFim() {
        val action = messagesEffect(
            messageAdded = true, proposalAdded = false, messageCount = 30,
            hasContext = true, hasTopicEmpty = false,
            lastItemIndex = 31, followEnd = true,
            initialLoad = true, atBottom = false,
        )
        assertTrue(action is MessagesEffect.ScrollTo)
        assertTrue((action as MessagesEffect.ScrollTo).instant)
        assertEquals(31, action.index)
    }

    @Test
    fun primeiraCargaComProposta_tambemEhInstantanea() {
        val action = messagesEffect(
            messageAdded = false, proposalAdded = true, messageCount = 2,
            hasContext = true, hasTopicEmpty = false,
            lastItemIndex = 4, followEnd = true,
            initialLoad = true, atBottom = false,
        )
        assertTrue(action is MessagesEffect.ScrollTo)
        assertTrue((action as MessagesEffect.ScrollTo).instant)
        assertEquals(4, action.index)
    }

    @Test
    fun seguindoMasForaDoFim_naoPuxaOUsuario_contaNova() {
        val action = messagesEffect(
            messageAdded = true, proposalAdded = false, messageCount = 6,
            hasContext = false, hasTopicEmpty = false,
            lastItemIndex = 7, followEnd = true,
            atBottom = false,
        )
        assertEquals(MessagesEffect.CountUnseen, action)
    }

    @Test
    fun seguindoENoFim_animaUmItem() {
        val action = messagesEffect(
            messageAdded = true, proposalAdded = false, messageCount = 6,
            hasContext = false, hasTopicEmpty = false,
            lastItemIndex = 7, followEnd = true,
            atBottom = true,
        )
        assertTrue(action is MessagesEffect.ScrollTo)
        assertFalse((action as MessagesEffect.ScrollTo).instant)
        assertEquals(5, action.index)
    }
}
