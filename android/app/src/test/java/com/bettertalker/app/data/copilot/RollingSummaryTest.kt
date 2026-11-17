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
    fun extractUserDecisions_pistaExplicitaDoUsuario() {
        val out = extractUserDecisions("Está decidido então, vamos fazer assim. E depois vemos o resto.")
        assertTrue(out.any { it.contains("decidido", ignoreCase = true) })
    }

    @Test
    fun extractUserDecisions_ignoraTagsDoModelo() {
        val out = extractUserDecisions("〈decisão〉usar a segunda abordagem〈/decisão〉")
        assertTrue(out.isEmpty())
    }

    @Test
    fun extractUserDecisions_naoTrataPerguntaNemRejeicaoComoDecisao() {
        assertTrue(extractUserDecisions("Vamos fazer assim?").isEmpty())
        assertTrue(extractUserDecisions("Decidido não usar a segunda melhoria.").isEmpty())
        assertTrue(extractUserDecisions("Não concordo com a segunda melhoria.").isEmpty())
    }

    @Test
    fun extractDecisions_semPista_retornaVazio() {
        assertTrue(extractDecisions("O que você acha deste ponto? Me explique melhor.").isEmpty())
    }

    @Test
    fun buildRollingSummary_tetoEDados() {
        val turns = (1..10).flatMap { i ->
            listOf(
                turn(true, "Decidido: usar a ideia $i."),
                turn(false, "Resposta $i. 〈decisão〉sugestão $i〈/decisão〉"),
            )
        }
        val s = buildRollingSummary(turns, "Ponto 2")
        assertEquals(10, s.userTurns)
        assertEquals("Ponto 2", s.sectionTitle)
        // Teto: só o fio recente, e somente falas do usuário.
        assertEquals(
            listOf(
                "Decidido: usar a ideia 6",
                "Decidido: usar a ideia 7",
                "Decidido: usar a ideia 8",
                "Decidido: usar a ideia 9",
                "Decidido: usar a ideia 10",
            ),
            s.decisions,
        )
        assertTrue(s.nextStep.startsWith("Decidido: usar a ideia 10"))
    }

    @Test
    fun buildRollingSummary_ignoraTagDoModelo() {
        val turns = listOf(
            turn(true, "Não concordo com a segunda melhoria."),
            turn(false, "Vou usar a segunda abordagem. 〈decisão〉usar a segunda abordagem〈/decisão〉"),
            turn(true, "Explique melhor."),
        )
        val s = buildRollingSummary(turns, "Ponto 2")
        assertTrue(s.decisions.isEmpty())
        assertFalse(renderMemoryBlock(s)?.contains("segunda abordagem") == true)
    }

    @Test
    fun buildRollingSummary_naoPromoveSugestaoEmLinguagemComum() {
        val turns = listOf(
            turn(true, "O que você acha?"),
            turn(false, "Sugiro usar uma pergunta na abertura e evitar ilustrações."),
        )
        assertTrue(buildRollingSummary(turns, "Ponto 1").decisions.isEmpty())
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
    fun mergeApproachText_acrescentaSemDuplicar() {
        assertEquals("- usar pergunta", mergeApproachText(null, "- usar pergunta"))
        assertEquals(
            "tom pastoral\n- usar pergunta",
            mergeApproachText("tom pastoral", "- usar pergunta"),
        )
        // Duplicada (case-insensitive) não entra de novo.
        assertNull(mergeApproachText("Tom Pastoral", "- tom pastoral"))
        assertNull(mergeApproachText("x", ""))
        assertNull(mergeApproachText("x", "  "))
    }

    @Test
    fun mergeApproachText_acumulaVariosTurnos() {
        // Aceite explícito de estrutura: acumula sem apagar o anterior.
        val after1 = mergeApproachText(null, "- abertura com pergunta")!!
        val after2 = mergeApproachText(after1, "- tom pastoral")!!
        assertTrue(after2.contains("abertura com pergunta"))
        assertTrue(after2.contains("tom pastoral"))
        // Reafirmar o mesmo trecho não duplica (nada muda).
        assertNull(mergeApproachText(after2, "- abertura com pergunta"))
    }

    @Test
    fun promptEfetivoNaoPromoveTagDoModeloADecisao() {
        val history = listOf(
            turn(true, "O que você acha?"),
            turn(false, "Vou usar a segunda abordagem. 〈decisão〉usar a segunda abordagem〈/decisão〉"),
        )
        val memory = renderMemoryBlock(buildRollingSummary(history, "Ponto 2"))
        val prompts = buildDeepSeekPrompts(
            message = "continue",
            history = history,
            isFirstMessage = false,
            pack = com.bettertalker.app.data.domain.ContextPack(emptyList(), emptyList()),
            blockTitle = "Ponto 2",
            blockMinutes = 5,
            blockText = "Texto do ponto.",
            contextBlock = memory,
        )
        // A sugestão continua no histórico, mas não como decisão de memória.
        assertTrue(prompts.user.contains("〈decisão〉"))
        assertFalse(prompts.system.contains("MEMÓRIA DA CONSTRUÇÃO"))
        assertFalse(prompts.system.contains("- Decisão: usar a segunda abordagem"))
    }

    @Test
    fun promptEfetivoMostraDecisaoExplicitaDoUsuario() {
        val history = listOf(
            turn(true, "Fechado: usar a segunda abordagem."),
            turn(false, "Certo, vou continuar por ela."),
        )
        val memory = renderMemoryBlock(buildRollingSummary(history, "Ponto 2"))
        assertTrue(memory!!.contains("Decisão: Fechado: usar a segunda abordagem"))
        val prompts = buildDeepSeekPrompts(
            message = "continue",
            history = history,
            isFirstMessage = false,
            pack = com.bettertalker.app.data.domain.ContextPack(emptyList(), emptyList()),
            blockTitle = "Ponto 2",
            blockMinutes = 5,
            blockText = "Texto do ponto.",
            contextBlock = memory,
        )
        assertTrue(prompts.system.contains("Decisão: Fechado: usar a segunda abordagem"))
    }

    @Test
    fun caudaInstruiMarcarDecisao() {
        assertTrue(CHAT_TAIL_INSTRUCTIONS.contains("〈decisão〉"))
        assertTrue(CHAT_TAIL_INSTRUCTIONS.contains("já aceitou"))
    }

    @Test
    fun renderMemoryBlock_naoVazaTags() {
        // O bloco carrega o conteúdo da decisão, nunca as tags.
        val block = renderMemoryBlock(RollingSummary("", listOf("x"), "", 9))
        assertFalse(block!!.contains("〈decisão〉"))
        assertFalse(block.contains("〈/decisão〉"))
    }
}
