package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #25-fix-a — o auto-scroll de mensagem nova mira a última MENSAGEM, não o
 * último item do LazyColumn: cards (fontes/proposta/estrutura) e estados
 * renderizam DEPOIS das mensagens e escondiam a resposta.
 */
class ChatLastMessageIndexTest {

    @Test
    fun tresMensagensSemCabecalho() {
        assertEquals(2, lastMessageIndex(3, hasContext = false, hasTopicEmpty = false))
    }

    @Test
    fun comContextoDeslocaUm() {
        assertEquals(3, lastMessageIndex(3, hasContext = true, hasTopicEmpty = false))
    }

    @Test
    fun comTopicoVazioDeslocaUm() {
        assertEquals(3, lastMessageIndex(3, hasContext = false, hasTopicEmpty = true))
    }

    @Test
    fun umaMensagem() {
        assertEquals(0, lastMessageIndex(1, hasContext = false, hasTopicEmpty = false))
    }

    @Test
    fun listaVaziaNuncaNegativo() {
        assertEquals(0, lastMessageIndex(0, hasContext = false, hasTopicEmpty = false))
    }

    @Test
    fun naoContaFontesNemCardsDoFim() {
        // 1 ctx + 3 msgs + fontes após = último ITEM 4 (não é a mensagem).
        assertEquals(4, chatLastItemIndex(3, true, false, true, false, 0))
        // 1 ctx + 3 msgs + proposta após = último ITEM 4.
        assertEquals(4, chatLastItemIndex(3, true, false, false, true, 0))
        // 1 ctx + 3 msgs + estado de execução = último ITEM 4.
        assertEquals(4, chatLastItemIndex(3, true, false, false, false, 1))
        // O alvo de mensagem ignora todos eles.
        assertEquals(3, lastMessageIndex(3, true, false))
    }
}
