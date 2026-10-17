package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #25-fix-a/#26-fix — a contagem de "N novas" é UMA por evento de conteúdo
 * novo (mensagem e/ou proposta); o `busy` não conta e remoções (fechar a
 * proposta) não contam. Espelha o efeito com o mesmo contrato (1 chamada por
 * mudança de `(messages.size, hasProposal)`).
 */
class PillCountTest {

    /** Espelha o `LaunchedEffect(messages.size, hasProposal)` da tela. */
    private class Counter {
        var unseen = 0
            private set
        var scrolledTo: Int? = null
            private set

        fun onEvent(
            messageAdded: Boolean,
            proposalAdded: Boolean,
            messageCount: Int,
            followEnd: Boolean,
            hasContext: Boolean = false,
            lastItemIndex: Int = 0,
        ) {
            when (val a = messagesEffect(
                messageAdded = messageAdded,
                proposalAdded = proposalAdded,
                messageCount = messageCount,
                hasContext = hasContext,
                hasTopicEmpty = false,
                lastItemIndex = lastItemIndex,
                followEnd = followEnd,
            )) {
                is MessagesEffect.ScrollTo -> scrolledTo = a.index
                MessagesEffect.CountUnseen -> unseen++
                MessagesEffect.Nothing -> Unit
            }
        }
    }

    @Test
    fun turnoComUmaMensagemNova_contaUm() {
        val c = Counter()
        // Evento único (5→6 mensagens). O busy true→false NÃO gera outro
        // evento — por isso não há mais o "3 novas" de antes.
        c.onEvent(messageAdded = true, proposalAdded = false, messageCount = 6, followEnd = false)
        assertEquals(1, c.unseen)
    }

    @Test
    fun duasMensagens_contamDuas_naoTres() {
        val c = Counter()
        c.onEvent(true, false, 6, false) // ME
        c.onEvent(true, false, 7, false) // resposta
        assertEquals(2, c.unseen)
    }

    @Test
    fun turnoSemMensagemNemProposta_naoConta() {
        val c = Counter()
        // Ex.: proposta FECHADA (remoção) — não é novidade.
        c.onEvent(false, false, 6, false)
        assertEquals(0, c.unseen)
    }

    @Test
    fun seguindoOFim_rolaSemContar() {
        val c = Counter()
        c.onEvent(true, false, 6, followEnd = true, hasContext = true)
        assertEquals(0, c.unseen)
        assertEquals(6, c.scrolledTo) // 1 ctx + 6 - 1
    }

    @Test
    fun listaVazia_nada() {
        assertEquals(
            MessagesEffect.Nothing,
            messagesEffect(false, false, 0, false, false, 0, followEnd = true),
        )
    }
}
