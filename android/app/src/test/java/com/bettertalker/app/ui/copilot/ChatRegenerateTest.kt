package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #26-fix-c — histórico do reenvio (Regenerar/Retry): a pergunta atual sai
 * da continuidade e vira o "texto atual" do prompt. Puro.
 */
class ChatRegenerateTest {

    private fun u(t: String) = ChatTurn(fromMe = true, text = t)
    private fun b(t: String) = ChatTurn(fromMe = false, text = t)

    @Test
    fun reenvio_removeApenasAUltimaPergunta() {
        val turns = listOf(u("p1"), b("r1"), u("p2"), b("r2"), u("p3"))
        assertEquals(listOf(u("p1"), b("r1"), u("p2"), b("r2")), historyForResend(turns))
    }

    @Test
    fun reenvio_semPerguntaNoFim_naoMudaNada() {
        val turns = listOf(u("p1"), b("r1"))
        assertEquals(turns, historyForResend(turns))
    }

    @Test
    fun reenvio_listaVazia_continuaVazia() {
        assertEquals(emptyList<ChatTurn>(), historyForResend(emptyList()))
    }
}
