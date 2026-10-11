package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 (TAIL condicional) — formato só quando a resposta pede estrutura.
 * Puro: sem LLM, rede, banco.
 */
class ChatTailFormatTest {

    private fun prompt(message: String) = buildChatPrompt(
        message = message,
        history = emptyList(),
        isFirstMessage = true,
        pack = ContextPack(emptyList(), emptyList()),
        blockTitle = null,
        blockMinutes = null,
        blockText = "x",
    )

    @Test
    fun pedidosDeGeracaoPedemEstrutura() {
        for (m in listOf(
            "desenvolva esse ponto", "escreva uma introdução", "crie uma ilustração",
            "sugira um gancho", "como faço essa transição?", "liste os pontos",
            "explique melhor", "resuma o bloco",
        )) {
            assertTrue(m, wantsStructuredAnswer(m))
        }
    }

    @Test
    fun conversaCurtaNaoPedeEstrutura() {
        for (m in listOf("o que acha?", "e se?", "gostei", "hmm")) {
            assertFalse(m, wantsStructuredAnswer(m))
        }
    }

    @Test
    fun caudaComEstruturaTemFormato() {
        val tail = chatTailInstructions(true)
        assertTrue(tail.contains("Abra com 1 frase"))
        assertTrue(tail.contains("〈sugestão〉"))
        assertTrue(tail.contains("frase de insuficiência"))
    }

    @Test
    fun caudaSemEstruturaMantemRegrasSemMolde() {
        val tail = chatTailInstructions(false)
        assertFalse(tail.contains("Abra com 1 frase"))
        assertTrue(tail.contains("〈sugestão〉"))
        assertTrue(tail.contains("ANTES de qualquer aspas"))
        assertTrue(tail.contains("TEXTOS BÍBLICOS"))
        assertTrue(tail.contains("TRECHOS DE PUBLICAÇÕES"))
    }

    @Test
    fun promptCurtoNaoFechaComFormato() {
        val p = prompt("o que acha?")
        assertFalse(p.contains("Abra com 1 frase"))
        assertTrue(p.contains("〈sugestão〉"))
    }

    @Test
    fun promptDeGeracaoMantemFormato() {
        val p = prompt("desenvolva esse ponto")
        assertTrue(p.contains("Abra com 1 frase"))
    }
}
