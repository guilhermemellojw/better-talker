package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 (Bug #13) — botões de ação como item próprio + alvo do auto-scroll ao
 * fim da lista. Puro/testável (sem Compose).
 */
class ChatActionsTest {

    private fun item(kind: String = "text", fromMe: Boolean = false) =
        CopilotViewModel.ChatItem(id = "m1", fromMe = fromMe, kind = kind)

    @Test
    fun respostaDeTextoDoCopilotTemAcoes() {
        assertTrue(hasMessageActions(item()))
    }

    @Test
    fun mensagemDoUsuarioNaoTemAcoes() {
        assertFalse(hasMessageActions(item(fromMe = true)))
    }

    @Test
    fun kindsComCorpoProprioNaoTemAcoes() {
        listOf("ideas", "refs", "bases", "sections", "draft").forEach { k ->
            assertFalse("kind=$k", hasMessageActions(item(kind = k)))
        }
    }

    @Test
    fun runStateItemCount() {
        assertEquals(0, runStateItemCount(ChatRunState.Idle, partialVisible = false))
        assertEquals(0, runStateItemCount(ChatRunState.Success, partialVisible = false))
        assertEquals(1, runStateItemCount(ChatRunState.Sending, partialVisible = false))
        assertEquals(1, runStateItemCount(ChatRunState.Generating, partialVisible = false))
        assertEquals(2, runStateItemCount(ChatRunState.Generating, partialVisible = true))
        assertEquals(1, runStateItemCount(ChatRunState.Error("x"), partialVisible = false))
        assertEquals(1, runStateItemCount(ChatRunState.Cancelled(), partialVisible = false))
    }

    @Test
    fun ultimoItemIncluiAcoesEItensDeFim() {
        // 1 contexto + 2 mensagens + 2 ações + 1 fontes = 6 itens → índice 5.
        assertEquals(5, chatLastItemIndex(2, 2, true, false, true, false, 0))
        // Mesma lista + proposta + 1 item de estado = 8 itens → índice 7.
        assertEquals(7, chatLastItemIndex(2, 2, true, false, true, true, 1))
    }

    @Test
    fun ultimoItemNuncaNegativo() {
        assertEquals(0, chatLastItemIndex(0, 0, false, false, false, false, 0))
        // Tópico vazio (sem contexto) = 1 item → índice 0.
        assertEquals(0, chatLastItemIndex(0, 0, false, true, false, false, 0))
    }
}
