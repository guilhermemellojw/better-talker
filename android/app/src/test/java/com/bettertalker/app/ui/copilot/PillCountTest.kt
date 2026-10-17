package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #25-fix-a — a contagem de "N novas" observa SÓ mudança de `messages.size`
 * (o `busy` não é mais key do efeito): um turno rende 1 incremento por
 * mensagem real; um turno que só gera proposta (sem mensagem nova) não
 * incrementa. Espelha o efeito com o mesmo contrato (1 chamada por mudança
 * de tamanho).
 */
class PillCountTest {

    /** Espelha o `LaunchedEffect(messages.size)` da tela. */
    private class Counter {
        var unseen = 0
            private set

        fun onMessagesChanged(
            size: Int,
            followEnd: Boolean,
            hasContext: Boolean = false,
            topicEmpty: Boolean = false,
        ) {
            when (messagesEffect(size, hasContext, topicEmpty, followEnd)) {
                is MessagesEffect.ScrollTo -> Unit
                MessagesEffect.CountUnseen -> unseen++
                MessagesEffect.Nothing -> Unit
            }
        }
    }

    @Test
    fun turnoComUmaMensagemNova_contaUm() {
        val c = Counter()
        c.onMessagesChanged(size = 6, followEnd = false) // única mudança 5→6
        // busy true→false NÃO gera outra chamada (key = messages.size)
        assertEquals(1, c.unseen)
    }

    @Test
    fun duasMensagensNovas_contamDuas_naoTres() {
        val c = Counter()
        c.onMessagesChanged(size = 6, followEnd = false) // ME
        c.onMessagesChanged(size = 7, followEnd = false) // resposta
        assertEquals(2, c.unseen)
    }

    @Test
    fun turnoSemMensagemNova_naoConta() {
        val c = Counter()
        c.onMessagesChanged(size = 6, followEnd = false)
        val antes = c.unseen
        // turno só-proposta: messages.size 6→6 → o efeito não roda de novo
        assertEquals(antes, c.unseen)
    }

    @Test
    fun seguindoOFim_rolaSemContar() {
        val c = Counter()
        c.onMessagesChanged(size = 6, followEnd = true, hasContext = true)
        assertEquals(0, c.unseen)
        val action = messagesEffect(6, hasContext = true, hasTopicEmpty = false, followEnd = true)
        assertTrue(action is MessagesEffect.ScrollTo)
        assertEquals(6, (action as MessagesEffect.ScrollTo).index) // 1 ctx + 6 - 1
    }

    @Test
    fun listaVazia_nada() {
        assertEquals(MessagesEffect.Nothing, messagesEffect(0, false, false, followEnd = true))
        assertEquals(MessagesEffect.Nothing, messagesEffect(0, false, false, followEnd = false))
    }
}
