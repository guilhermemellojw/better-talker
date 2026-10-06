package com.bettertalker.app

import com.bettertalker.app.data.copilot.INSUFFICIENT_EVIDENCE_MESSAGE
import com.bettertalker.app.data.copilot.contextualScopeTip
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3 — dica contextual para vincular publicações quando o escopo está vazio. */
class ContextualTipTest {

    private val insuficiente =
        "$INSUFFICIENT_EVIDENCE_MESSAGE\n\n〈sugestão〉Posso oferecer uma redação minha."

    @Test
    fun dicaApareceComInsuficienciaEscopoVazioEPublicacoes() {
        val tip = contextualScopeTip(insuficiente, unlinkedPublications = 3, scopeEmpty = true)
        assertTrue(tip!!.contains("3 publicação"))
        assertTrue(tip.contains("Anexar do acervo"))
    }

    @Test
    fun semDicaQuandoEscopoNaoEstaVazio() {
        assertNull(contextualScopeTip(insuficiente, unlinkedPublications = 3, scopeEmpty = false))
    }

    @Test
    fun semDicaSemPublicacoesOuRespostaNormal() {
        assertNull(contextualScopeTip(insuficiente, unlinkedPublications = 0, scopeEmpty = true))
        assertNull(
            contextualScopeTip(
                "Resposta normal com fontes.",
                unlinkedPublications = 5,
                scopeEmpty = true
            )
        )
    }
}
