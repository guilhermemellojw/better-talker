package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 (modo colar e conversar) — prompt minimalista e detecção de colagem.
 * Puro: sem LLM, rede, banco.
 */
class MinimalPasteTest {

    private val esboco = "Ponto 1: a fé. Ponto 2: a esperança. " +
        "Texto corrido do esboço colado pelo usuário."

    // ---------- builder minimalista ----------

    @Test
    fun systemTemTresLinhasEFalaDeInsuficiencia() {
        val (system, _) = buildMinimalPastePrompts(esboco, "o que acha?", emptyList())
        assertTrue(system.contains("copiloto de oratória"))
        assertTrue(system.contains("〈sugestão〉"))
        assertTrue(system.contains(INSUFFICIENT_EVIDENCE_MESSAGE))
    }

    @Test
    fun userTemEsbocoIntegroEMensagem() {
        val (_, user) = buildMinimalPastePrompts(
            esboco, "sugira uma introdução",
            listOf(ChatTurn(true, "oi"))
        )
        assertTrue(user.contains(esboco))
        assertTrue(user.contains("sugira uma introdução"))
        assertTrue(user.contains("oi"))
    }

    @Test
    fun esbocoTruncaEm20000() {
        val grande = "x".repeat(MINIMAL_PASTE_MAX_CHARS + 1)
        val (_, user) = buildMinimalPastePrompts(grande, "resuma", emptyList())
        assertTrue(user.contains("x".repeat(MINIMAL_PASTE_MAX_CHARS)))
        assertFalse(user.contains("x".repeat(MINIMAL_PASTE_MAX_CHARS + 1)))
    }

    @Test
    fun semTailSemRegras() {
        val (system, user) = buildMinimalPastePrompts(esboco, "o que acha?", emptyList())
        val tudo = "$system\n$user"
        assertFalse(tudo.contains("Abra com 1 frase"))
        assertFalse(tudo.contains("REGRAS DO S-34"))
        assertFalse(tudo.contains("DERIVADA do S-34"))
        assertFalse(tudo.contains("FONTES DE CONTEÚDO"))
    }

    // ---------- detecção de colagem ----------

    @Test
    fun textoLongoECandidato() {
        assertTrue(ChatRouter.isPasteCandidate("x".repeat(501)))
    }

    @Test
    fun textoCurtoNaoECandidato() {
        assertFalse(ChatRouter.isPasteCandidate("x".repeat(500)))
        assertFalse(ChatRouter.isPasteCandidate("Desenvolva o ponto 2."))
        assertFalse(ChatRouter.isPasteCandidate("   "))
    }

    @Test
    fun describeDaRota() {
        assertEquals(
            "route=PASTE_AND_CHAT reason=esboço colado",
            ChatRouter.describe(ChatRouter.Route.PasteAndChat("esboço colado"))
        )
    }
}
