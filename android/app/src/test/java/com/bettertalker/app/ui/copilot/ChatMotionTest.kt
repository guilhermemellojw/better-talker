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
                hasTopicEmpty = false,
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
                hasTopicEmpty = false,
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
                hasTopicEmpty = false,
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
                hasTopicEmpty = false,
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
                hasTopicEmpty = false,
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
                hasTopicEmpty = true,
                activeTurnVisible = true,
                itemsBeforeTurn = 2,
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
                hasTopicEmpty = false,
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
}
