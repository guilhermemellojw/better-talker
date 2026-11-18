package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.data.copilot.TurnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMotionTest {

    private fun turn(phase: TurnPhase) = TurnUiState(turnId = "t1", phase = phase)

    @Test
    fun erroAvulso_soSemTurnoAtivo() {
        assertTrue(shouldShowStandaloneError(null, ChatRunState.Error("falhou")))
        assertTrue(shouldShowStandaloneError(null, ChatRunState.Cancelled("parada")))
        assertFalse(shouldShowStandaloneError(null, ChatRunState.Idle))
        assertFalse(shouldShowStandaloneError(null, ChatRunState.Sending))
        assertFalse(shouldShowStandaloneError(null, ChatRunState.Generating))
        assertFalse(shouldShowStandaloneError(null, ChatRunState.Success))
    }

    @Test
    fun erroAvulso_nuncaComTurnoAtivo() {
        for (phase in TurnPhase.entries) {
            assertFalse(
                "fase $phase",
                shouldShowStandaloneError(turn(phase), ChatRunState.Error("falhou")),
            )
            assertFalse(
                "fase $phase",
                shouldShowStandaloneError(turn(phase), ChatRunState.Cancelled("parada")),
            )
        }
    }

    @Test
    fun motionMillis_respeitaReducedMotionEEscala() {
        assertEquals(0, motionMillis(180, 0f))
        assertEquals(0, motionMillis(180, -1f))
        assertEquals(90, motionMillis(180, 0.5f))
        assertEquals(180, motionMillis(180, 1f))
        assertEquals(1, motionMillis(1, 0.1f))
    }

    // ---------- UX-2.1: fim real da lista ----------

    @Test
    fun fimReal_listaVazia() {
        assertTrue(
            isAtListBottom(
                totalItems = 0,
                lastVisibleIndex = null,
                lastVisibleEnd = 0,
                viewportEnd = 0,
            )
        )
    }

    @Test
    fun fimReal_ultimoItemExatamenteNoFim() {
        assertTrue(
            isAtListBottom(
                totalItems = 4,
                lastVisibleIndex = 3,
                lastVisibleEnd = 1600,
                viewportEnd = 1600,
            )
        )
    }

    @Test
    fun fimReal_listaMenorQueViewport() {
        assertTrue(
            isAtListBottom(
                totalItems = 2,
                lastVisibleIndex = 1,
                lastVisibleEnd = 800,
                viewportEnd = 2000,
            )
        )
    }

    @Test
    fun fimReal_ultimoItemUltrapassaOFim() {
        assertFalse(
            isAtListBottom(
                totalItems = 4,
                lastVisibleIndex = 3,
                lastVisibleEnd = 2200,
                viewportEnd = 1600,
            )
        )
    }

    @Test
    fun fimReal_itemMaiorQueViewportLendoOMeio() {
        // Último item visível, mas ainda há conteúdo abaixo da viewport.
        assertFalse(
            isAtListBottom(
                totalItems = 3,
                lastVisibleIndex = 2,
                lastVisibleEnd = 2600,
                viewportEnd = 2000,
            )
        )
    }

    @Test
    fun fimReal_dentroDaTolerancia() {
        assertTrue(
            isAtListBottom(
                totalItems = 3,
                lastVisibleIndex = 2,
                lastVisibleEnd = 2008,
                viewportEnd = 2000,
                tolerancePx = 8,
            )
        )
        assertFalse(
            isAtListBottom(
                totalItems = 3,
                lastVisibleIndex = 2,
                lastVisibleEnd = 2009,
                viewportEnd = 2000,
                tolerancePx = 8,
            )
        )
    }

    @Test
    fun fimReal_penultimoVisivelUltimoFora() {
        assertFalse(
            isAtListBottom(
                totalItems = 3,
                lastVisibleIndex = 1,
                lastVisibleEnd = 1500,
                viewportEnd = 2000,
            )
        )
    }

    @Test
    fun fimReal_transitorioSemItensVisiveisNaoReata() {
        // Layout ainda não publicou visibleItemsInfo: não é fim.
        assertFalse(
            isAtListBottom(
                totalItems = 3,
                lastVisibleIndex = null,
                lastVisibleEnd = 0,
                viewportEnd = 2000,
            )
        )
    }

    @Test
    fun fimReal_transicaoDoMeioParaOFim() {
        val meio = isAtListBottom(3, 2, 2600, 2000)
        assertFalse(meio)
        val fim = isAtListBottom(3, 2, 1996, 2000)
        assertTrue(fim)
    }

    @Test
    fun fimReal_preservaDecisaoDoFollowEnd() {
        // Regressão: no meio de uma resposta alta, não acompanhar; no fim, acompanhar.
        val meio = isAtListBottom(3, 2, 2600, 2000)
        assertEquals(
            MessagesEffect.CountUnseen,
            messagesEffect(
                messageAdded = true,
                proposalAdded = false,
                messageCount = 3,
                hasContext = false,
                hasTopicEmpty = false,
                lastItemIndex = 2,
                followEnd = true,
                atBottom = meio,
            ),
        )
        val fim = isAtListBottom(3, 2, 1996, 2000)
        assertTrue(
            messagesEffect(
                messageAdded = true,
                proposalAdded = false,
                messageCount = 3,
                hasContext = false,
                hasTopicEmpty = false,
                lastItemIndex = 2,
                followEnd = true,
                atBottom = fim,
            ) is MessagesEffect.ScrollTo
        )
    }

    @Test
    fun turnAnchorIndex_usaTurnoNaoCardFinal() {
        // sem contexto: 3 mensagens (0..2) + turno visível = índice 3.
        assertEquals(3, turnAnchorIndex(3, false, false, activeTurnVisible = true))
        // com contexto: desloca 1; turno = 1 + 3 = 4.
        assertEquals(4, turnAnchorIndex(3, true, false, activeTurnVisible = true))
        // sem turno visível: última mensagem (não card/proposta).
        assertEquals(2, turnAnchorIndex(3, false, false, activeTurnVisible = false))
        assertEquals(3, turnAnchorIndex(3, true, false, activeTurnVisible = false))
        // tópico vazio + contexto: o turno vem depois dos dois slots.
        assertEquals(2, turnAnchorIndex(0, true, true, activeTurnVisible = true))
    }

    // ---------- UX-2.2: âncora do streaming ----------

    @Test
    fun turnAnchorIndex_comProveniencia() {
        // 3 mensagens + proveniência + turno = índice 4.
        assertEquals(
            4,
            turnAnchorIndex(
                messageCount = 3,
                hasContext = false,
                hasThreadEmpty = false,
                activeTurnVisible = true,
                itemsBeforeTurn = 1,
            ),
        )
    }

    @Test
    fun turnAnchorIndex_comFocusSourcesSemEvidence() {
        val provenance = provenanceItemCount(evidenceCount = 0, hasFocusSources = true)
        assertEquals(1, provenance)
        assertEquals(
            4,
            turnAnchorIndex(
                messageCount = 3,
                hasContext = false,
                hasThreadEmpty = false,
                activeTurnVisible = true,
                itemsBeforeTurn = provenance,
            ),
        )
    }

    @Test
    fun turnAnchorIndex_comUmaProposta() {
        assertEquals(
            4,
            turnAnchorIndex(
                messageCount = 3,
                hasContext = false,
                hasThreadEmpty = false,
                activeTurnVisible = true,
                itemsBeforeTurn = proposalItemCount(true, false, false, false, false),
            ),
        )
    }

    @Test
    fun turnAnchorIndex_comMultiplasPropostas() {
        val proposals = proposalItemCount(true, true, true, true, false)
        assertEquals(4, proposals)
        assertEquals(
            7,
            turnAnchorIndex(
                messageCount = 3,
                hasContext = false,
                hasThreadEmpty = false,
                activeTurnVisible = true,
                itemsBeforeTurn = proposals,
            ),
        )
    }

    @Test
    fun turnAnchorIndex_comProvenienciaEMultiplasPropostas() {
        val aux = provenanceItemCount(evidenceCount = 2, hasFocusSources = false) +
            proposalItemCount(true, true, false, false, false)
        assertEquals(3, aux)
        assertEquals(
            7,
            turnAnchorIndex(
                messageCount = 3,
                hasContext = true,
                hasThreadEmpty = false,
                activeTurnVisible = true,
                itemsBeforeTurn = aux,
            ),
        )
    }

    @Test
    fun turnAnchorIndex_topicoVazioComAuxiliares() {
        assertEquals(
            4,
            turnAnchorIndex(
                messageCount = 0,
                hasContext = true,
                hasThreadEmpty = true,
                activeTurnVisible = true,
                itemsBeforeTurn = 2,
            ),
        )
    }

    @Test
    fun turnAnchorIndex_threadEmptyEmDiscursoInteiroComTurnoAtivo() {
        // messages.isEmpty() e chatScope == null: a LazyColumn ainda renderiza
        // "thread-empty"; o turno ativo vem depois dele.
        assertEquals(
            1,
            turnAnchorIndex(
                messageCount = 0,
                hasContext = false,
                hasThreadEmpty = true,
                activeTurnVisible = true,
            ),
        )
    }

    @Test
    fun turnAnchorIndex_semTurnoIgnoraAuxiliares() {
        // Sem turno visível, o alvo continua sendo a última mensagem.
        assertEquals(
            2,
            turnAnchorIndex(
                messageCount = 3,
                hasContext = false,
                hasThreadEmpty = false,
                activeTurnVisible = false,
                itemsBeforeTurn = 99,
            ),
        )
    }

    @Test
    fun provenanceItemCount_soContaQuandoHaEvidenceOuFocusSources() {
        assertEquals(0, provenanceItemCount(evidenceCount = 0, hasFocusSources = false))
        assertEquals(1, provenanceItemCount(evidenceCount = 3, hasFocusSources = false))
        assertEquals(1, provenanceItemCount(evidenceCount = 0, hasFocusSources = true))
        assertEquals(1, provenanceItemCount(evidenceCount = 3, hasFocusSources = true))
    }

    @Test
    fun proposalItemCount_contaCadaPropostaRenderizada() {
        assertEquals(0, proposalItemCount(false, false, false, false, false))
        assertEquals(1, proposalItemCount(true, false, false, false, false))
        assertEquals(2, proposalItemCount(true, false, false, false, true))
        assertEquals(4, proposalItemCount(true, true, true, true, false))
        assertEquals(5, proposalItemCount(true, true, true, true, true))
    }

    @Test
    fun visualGroup_agrupaStatusEErro() {
        assertEquals(TurnVisualGroup.STATUS, TurnPhase.WAITING.visualGroup())
        assertEquals(TurnVisualGroup.STATUS, TurnPhase.REASONING.visualGroup())
        assertEquals(TurnVisualGroup.STREAMING, TurnPhase.STREAMING.visualGroup())
        assertEquals(TurnVisualGroup.DONE, TurnPhase.DONE.visualGroup())
        assertEquals(TurnVisualGroup.ERROR, TurnPhase.CANCELLED.visualGroup())
        assertEquals(TurnVisualGroup.ERROR, TurnPhase.ERROR.visualGroup())
    }

    // ---------- UX-2.3: handoff estável ----------

    @Test
    fun hasHiddenTurnMessage_true_apenasQuandoTurnoVisivelEOcultaPersistida() {
        val turn = TurnUiState(turnId = "t1", phase = TurnPhase.STREAMING)
        val messages = listOf(
            CopilotViewModel.ChatItem(id = "m1", fromMe = false, kind = "text", turnId = "t1"),
        )
        assertTrue(hasHiddenTurnMessage(true, turn, messages))
        assertFalse(hasHiddenTurnMessage(false, turn, messages))
        assertFalse(hasHiddenTurnMessage(true, null, messages))
        val other = listOf(
            CopilotViewModel.ChatItem(id = "m2", fromMe = false, kind = "text", turnId = "t2"),
        )
        assertFalse(hasHiddenTurnMessage(true, turn, other))
    }

    @Test
    fun renderedMessageCount_subtraiMensagemOculta() {
        assertEquals(2, renderedMessageCount(3, true))
        assertEquals(3, renderedMessageCount(3, false))
        assertEquals(0, renderedMessageCount(1, true))
        assertEquals(0, renderedMessageCount(0, true))
    }

    @Test
    fun isTurnHandoff_identificaPorTurnId() {
        val turn = TurnUiState(turnId = "t1", phase = TurnPhase.DONE)
        val messages = listOf(
            CopilotViewModel.ChatItem(id = "m1", fromMe = false, kind = "text", turnId = "t1"),
        )
        assertTrue(isTurnHandoff(turn, messages))
        assertFalse(isTurnHandoff(turn, emptyList()))
        assertFalse(isTurnHandoff(null, messages))
        val userMsg = listOf(
            CopilotViewModel.ChatItem(id = "m2", fromMe = true, kind = "text", turnId = "t1"),
        )
        assertFalse(isTurnHandoff(turn, userMsg))
    }

    @Test
    fun isTurnHandoff_porIdPrimitivo() {
        assertTrue(isTurnHandoff("t1", "t1", false))
        assertFalse(isTurnHandoff("t1", "t1", true))
        assertFalse(isTurnHandoff("t1", "t2", false))
        assertFalse(isTurnHandoff(null, "t1", false))
    }

    @Test
    fun messagesEffect_semHandoff_comportamentoNormal() {
        assertEquals(
            MessagesEffect.Nothing,
            messagesEffect(
                messageAdded = false,
                proposalAdded = false,
                messageCount = 3,
                hasContext = false,
                hasTopicEmpty = false,
                lastItemIndex = 2,
                followEnd = true,
                atBottom = true,
            ),
        )
        assertTrue(
            messagesEffect(
                messageAdded = true,
                proposalAdded = false,
                messageCount = 3,
                hasContext = false,
                hasTopicEmpty = false,
                lastItemIndex = 2,
                followEnd = true,
                atBottom = true,
            ) is MessagesEffect.ScrollTo,
        )
    }

    @Test
    fun isTurnHandoff_verdadeiroEmPosicaoIntermediaria() {
        val turn = TurnUiState(turnId = "t1", phase = TurnPhase.DONE)
        val messages = listOf(
            CopilotViewModel.ChatItem(id = "m1", fromMe = false, kind = "text", turnId = "t1"),
            CopilotViewModel.ChatItem(id = "m2", fromMe = true, kind = "text", turnId = "other"),
        )
        assertTrue(isTurnHandoff(turn, messages))
        // A contagem oculta só vale se o turno ainda estiver visível.
        assertTrue(hasHiddenTurnMessage(true, turn, messages))
        assertEquals(1, renderedMessageCount(messages.size, true))
    }

    @Test
    fun isTurnHandoff_naoConfundeOutroTurnoNaUltimaPosicao() {
        val turn = TurnUiState(turnId = "t1", phase = TurnPhase.DONE)
        val messages = listOf(
            CopilotViewModel.ChatItem(id = "m1", fromMe = false, kind = "text", turnId = "t1"),
            CopilotViewModel.ChatItem(id = "m2", fromMe = false, kind = "text", turnId = "t2"),
        )
        // A resposta do turno existe, mas a última mensagem é de outro turno:
        // a identidade ainda é reconhecida pela presença, não pela posição.
        assertTrue(isTurnHandoff(turn, messages))
    }

    @Test
    fun messagesEffect_followEndFalse_contaNovaMensagem() {
        assertEquals(
            MessagesEffect.CountUnseen,
            messagesEffect(
                messageAdded = true,
                proposalAdded = false,
                messageCount = 3,
                hasContext = false,
                hasTopicEmpty = false,
                lastItemIndex = 2,
                followEnd = false,
                atBottom = false,
            ),
        )
    }
    @Test
    fun isTurnHandoffArrival_eventoUnico() {
        assertTrue(isTurnHandoffArrival(true, false))
        assertFalse(isTurnHandoffArrival(true, true))
        assertFalse(isTurnHandoffArrival(false, false))
        assertFalse(isTurnHandoffArrival(false, true))
    }

    @Test
    fun isTurnHandoff_presencaNaoEhEvento() {
        val turn = TurnUiState(turnId = "t1", phase = TurnPhase.DONE)
        val messages = listOf(
            CopilotViewModel.ChatItem(id = "m1", fromMe = false, kind = "text", turnId = "t1"),
        )
        // Presença permanece verdadeira enquanto a resposta existir.
        assertTrue(isTurnHandoff(turn, messages))
        // O evento de chegada é separado e pode ser consumido.
        assertTrue(isTurnHandoffArrival(isTurnHandoff(turn, messages), false))
        assertFalse(isTurnHandoffArrival(isTurnHandoff(turn, messages), true))
    }
}
