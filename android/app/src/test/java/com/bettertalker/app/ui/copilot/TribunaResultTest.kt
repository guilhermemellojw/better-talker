package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato do resultado da Tribuna: cada falha conhecida oferece só as ações
 * que destravam o caso — configurar IA sem chave, abrir editor sem texto,
 * tentar de novo nos erros do provider/resposta.
 */
class TribunaResultTest {

    @Test
    fun faltaChave_deIa_soOfereceConfigurarIa() {
        val failure = TribunaFailures.noAiKey()
        assertTrue(failure.canConfigureAi)
        assertFalse(failure.canOpenEditor)
        assertTrue(failure.message.contains("Modelo IA"))
    }

    @Test
    fun secoesVazias_ofereceAbrirEditor() {
        val failure = TribunaFailures.emptySections()
        assertTrue(failure.canOpenEditor)
        assertFalse(failure.canConfigureAi)
        assertTrue(failure.message.contains("seções ainda estão vazias"))
    }

    @Test
    fun truncamento_ofereceAbrirEditorParaEncurtar() {
        val failure = TribunaFailures.truncated()
        assertTrue(failure.canOpenEditor)
        assertTrue(failure.message.contains("limite de tokens"))
    }

    @Test
    fun falhaDoProvider_naoDesviaParaEditorNemIa() {
        val failure = TribunaFailures.providerFailed()
        assertFalse(failure.canOpenEditor)
        assertFalse(failure.canConfigureAi)
    }

    @Test
    fun respostaVazia_soPedeTentarNovamente() {
        val failure = TribunaFailures.emptyResponse()
        assertFalse(failure.canOpenEditor)
        assertFalse(failure.canConfigureAi)
        assertTrue(failure.message.contains("formato inesperado"))
    }

    @Test
    fun notaAusente_naoOfereceAcoes() {
        val failure = TribunaFailures.noNote()
        assertFalse(failure.canOpenEditor)
        assertFalse(failure.canConfigureAi)
    }
}
