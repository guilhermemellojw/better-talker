package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/** #26-fix — botão primário adaptativo: seção vazia usa; com conteúdo adiciona. */
class ProposalButtonLabelTest {

    @Test
    fun secaoVazia_labelUsar() {
        assertEquals("Usar este texto", proposalButtonLabel(""))
        assertEquals("Usar este texto", proposalButtonLabel("   "))
    }

    @Test
    fun secaoComConteudo_labelAdicionar() {
        assertEquals("Adicionar ao final", proposalButtonLabel("abc"))
    }
}
