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
    fun sugestoesDoOlaSoAntesDaPrimeiraMensagem() {
        assertTrue(shouldShowSuggestions(hasUserMessage = false, inputBlank = true))
        assertFalse(shouldShowSuggestions(hasUserMessage = true, inputBlank = true))
        assertFalse(shouldShowSuggestions(hasUserMessage = false, inputBlank = false))
        assertFalse(shouldShowSuggestions(hasUserMessage = true, inputBlank = false))
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

    // ---------- T6 (Fase 2.1): atalhos realocados do sheet "Ferramentas" ----------

    @Test
    fun chipsCobremOsAtalhosDoSheetFerramentas() {
        // "Ferramentas" saiu do composer; os comandos vivem nos chips da
        // conversa vazia (sem perda de feature).
        val chips = localSuggestionTexts(hasOutline = true)
        listOf(
            "Resumir",
            "Quais refs faltam?",
            "Mostre as seções",
            "Refs do esboço",
            "Sincronizar esboço",
        ).forEach { cmd -> assertTrue("faltou: $cmd", cmd in chips) }
    }

    @Test
    fun semEsbocoChipsBasicosContinuam() {
        val chips = localSuggestionTexts(hasOutline = false)
        assertTrue("Resumir" in chips)
        assertTrue("Quais refs faltam?" in chips)
        assertFalse("Sincronizar esboço" in chips)
    }

    @Test
    fun runStateItemCount() {
        assertEquals(0, runStateItemCount(ChatRunState.Idle))
        assertEquals(0, runStateItemCount(ChatRunState.Success))
        assertEquals(1, runStateItemCount(ChatRunState.Sending))
        // #26-fix-c (UI): o indicador "Pensando…" e o parcial ocupam o MESMO
        // slot — o estado de execução vale sempre 1 item.
        assertEquals(1, runStateItemCount(ChatRunState.Generating))
        assertEquals(1, runStateItemCount(ChatRunState.Error("x")))
        assertEquals(1, runStateItemCount(ChatRunState.Cancelled()))
    }

    @Test
    fun ultimoItemIncluiItensDeFim() {
        // #22-fix: ações agora vivem DENTRO do item da mensagem.
        // 1 contexto + 2 mensagens + 1 fontes = 4 itens → índice 3.
        assertEquals(3, chatLastItemIndex(2, true, false, true, false, 0))
        // Mesma lista + proposta + 1 item de estado = 6 itens → índice 5.
        assertEquals(5, chatLastItemIndex(2, true, false, true, true, 1))
    }

    @Test
    fun ultimoItemNuncaNegativo() {
        assertEquals(0, chatLastItemIndex(0, false, false, false, false, 0))
        // Tópico vazio (sem contexto) = 1 item → índice 0.
        assertEquals(0, chatLastItemIndex(0, false, true, false, false, 0))
    }

    @Test
    fun ultimaMensagemIgnoraCardsDoFim() {
        // #25-fix-a: com fontes + cards/proposta após as mensagens, o alvo do
        // scroll de mensagem nova continua sendo a MENSAGEM; o "último item"
        // (chatLastItemIndex) aponta o card — era ele que escondia a resposta.
        assertEquals(2, lastMessageIndex(3, hasContext = false, hasTopicEmpty = false))
        // 1 ctx + 3 msgs + fontes + proposta = 6 itens → último índice 5.
        assertEquals(5, chatLastItemIndex(3, true, false, true, true, 0))
    }
}
