package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 (Fase 2) — memória da construção: extração de consenso, resumo
 * rolante e bloco de memória. Puro/testável (sem Room/LLM).
 */
class RollingSummaryTest {

    private fun turn(fromMe: Boolean, text: String) = ChatTurn(fromMe, text)

    @Test
    fun extractDecisions_leTagsDoModelo() {
        val out = extractDecisions(
            "Boa! 〈decisão〉abertura com pergunta〈/decisão〉 e depois " +
                "〈decisão〉tom pastoral〈/decisão〉."
        )
        assertEquals(listOf("abertura com pergunta", "tom pastoral"), out)
    }

    @Test
    fun extractDecisions_pistaExplicitaDoUsuario() {
        val out = extractDecisions("Está decidido então, vamos fazer assim. E depois vemos o resto.")
        assertTrue(out.any { it.contains("decidido", ignoreCase = true) })
    }

    @Test
    fun extractDecisions_semPista_retornaVazio() {
        assertTrue(extractDecisions("O que você acha deste ponto? Me explique melhor.").isEmpty())
    }

    @Test
    fun buildRollingSummary_tetoEDados() {
        val turns = (1..10).flatMap { i ->
            listOf(
                turn(true, "Mensagem $i sobre o ponto."),
                turn(false, "Resposta $i. 〈decisão〉decisão $i〈/decisão〉"),
            )
        }
        val s = buildRollingSummary(turns, "Ponto 2")
        assertEquals(10, s.userTurns)
        assertEquals("Ponto 2", s.sectionTitle)
        // Teto: só o fio recente.
        assertEquals(
            listOf("decisão 6", "decisão 7", "decisão 8", "decisão 9", "decisão 10"),
            s.decisions,
        )
        assertTrue(s.nextStep.startsWith("Mensagem 10"))
    }

    @Test
    fun renderMemoryBlock_curtaSemDecisao_retornaNull() {
        val s = buildRollingSummary(listOf(turn(true, "oi"), turn(false, "olá")), "")
        assertNull(renderMemoryBlock(s))
    }

    @Test
    fun renderMemoryBlock_longaOuComDecisao_temBloco() {
        val withDecision = RollingSummary("Ponto 1", listOf("abertura com pergunta"), "", 2)
        val block = renderMemoryBlock(withDecision)
        assertTrue(block!!.contains("## MEMÓRIA DA CONSTRUÇÃO"))
        assertTrue(block.contains("Seção atual: Ponto 1"))
        assertTrue(block.contains("- Decisão: abertura com pergunta"))

        val long = RollingSummary("", emptyList(), "desenvolva o ponto 2", SUMMARY_EVERY_TURNS)
        val block2 = renderMemoryBlock(long)
        assertTrue(block2!!.contains("Próximo passo: desenvolva o ponto 2"))
    }

    @Test
    fun mergeApproach_acrescentaSemDuplicar() {
        assertEquals("- usar pergunta", mergeApproach(null, listOf("usar pergunta")))
        assertEquals(
            "tom pastoral\n- usar pergunta",
            mergeApproach("tom pastoral", listOf("usar pergunta")),
        )
        // Duplicada (case-insensitive) não entra de novo.
        assertNull(mergeApproach("Tom Pastoral", listOf("tom pastoral")))
        assertNull(mergeApproach("x", emptyList()))
        assertNull(mergeApproach("x", listOf("  ")))
    }

    @Test
    fun mergeApproach_acumulaVariosTurnos() {
        // Conversa longa: cada turno acrescenta sem apagar o anterior.
        val after1 = mergeApproach(null, listOf("abertura com pergunta"))!!
        val after2 = mergeApproach(after1, listOf("tom pastoral"))!!
        assertTrue(after2.contains("abertura com pergunta"))
        assertTrue(after2.contains("tom pastoral"))
        // Reafirmar o consenso não duplica (nada muda).
        assertNull(mergeApproach(after2, listOf("abertura com pergunta")))
    }

    @Test
    fun caudaInstruiMarcarDecisao() {
        assertTrue(CHAT_TAIL_INSTRUCTIONS.contains("〈decisão〉"))
    }

    @Test
    fun renderMemoryBlock_naoVazaTags() {
        // O bloco carrega o conteúdo da decisão, nunca as tags.
        val block = renderMemoryBlock(RollingSummary("", listOf("x"), "", 9))
        assertFalse(block!!.contains("〈decisão〉"))
        assertFalse(block.contains("〈/decisão〉"))
    }
}
