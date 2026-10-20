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
        assertTrue(ChatRouter.isPasteCandidate("x".repeat(501), fromClipboard = true))
    }

    @Test
    fun textoCurtoNaoECandidato() {
        assertFalse(ChatRouter.isPasteCandidate("x".repeat(500), fromClipboard = true))
        assertFalse(ChatRouter.isPasteCandidate("Desenvolva o ponto 2.", fromClipboard = true))
        assertFalse(ChatRouter.isPasteCandidate("   ", fromClipboard = true))
    }

    @Test
    fun textoLongoDigitadoNaoECandidato() {
        // #26-fix-b: sem clipboard real, texto longo NÃO vira "material colado".
        assertFalse(ChatRouter.isPasteCandidate("x".repeat(501), fromClipboard = false))
    }

    @Test
    fun clipboardMatches_exigeTextoIdentico() {
        assertTrue(clipboardMatches("esboço colado", "esboço colado"))
        assertTrue(clipboardMatches("  esboço colado  ", "esboço colado"))
        assertFalse(clipboardMatches("digitado", null))
        assertFalse(clipboardMatches("digitado", "outra coisa"))
        assertFalse(clipboardMatches("", ""))
    }

    @Test
    fun describeDaRota() {
        assertEquals(
            "route=PASTE_AND_CHAT reason=esboço colado",
            ChatRouter.describe(ChatRouter.Route.PasteAndChat("esboço colado"))
        )
    }

    // ---------- T1 (Fase 2.1): memória da construção no modo colar ----------

    @Test
    fun semResumoPromptMinimalistaFicaInalterado() {
        assertEquals("", minimalMemoryPrefix(null))
        assertEquals("", minimalMemoryPrefix(""))
        assertEquals("", minimalMemoryPrefix("   "))
        val (system, user) = buildMinimalPastePrompts(esboco, "o que acha?", emptyList())
        assertFalse(system.contains("MEMÓRIA DA CONSTRUÇÃO"))
        assertFalse(user.contains("MEMÓRIA DA CONSTRUÇÃO"))
    }

    @Test
    fun comResumoMemoriaEntraComoConteudoNaNensagem() {
        val resumo = renderMemoryBlock(
            RollingSummary(
                decisions = listOf("mensagem em 3 pontos: fé, esperança e amor"),
                nextStep = "insira tudo na nota",
                userTurns = 3,
            )
        )
        assertTrue(resumo != null)
        val prefixo = minimalMemoryPrefix(resumo)
        assertTrue(prefixo.contains("## MEMÓRIA DA CONSTRUÇÃO"))
        assertTrue(prefixo.contains("fé, esperança e amor"))
        val (system, user) = buildMinimalPastePrompts(esboco, prefixo + "e agora?", emptyList())
        assertTrue(user.contains("## MEMÓRIA DA CONSTRUÇÃO"))
        assertTrue(user.contains("e agora?"))
        // O system segue minimalista — a memória é conteúdo, não instrução.
        assertFalse(system.contains("MEMÓRIA DA CONSTRUÇÃO"))
    }

    @Test
    fun resumoGrandeTruncadoNoTeto() {
        val linha = "- Decisão: " + "x".repeat(60)
        val grande = "## MEMÓRIA DA CONSTRUÇÃO\n" + (1..20).joinToString("\n") { linha }
        val prefixo = minimalMemoryPrefix(grande)
        val bloco = prefixo.trimEnd()
        assertTrue(bloco.endsWith("…"))
        assertTrue(bloco.length <= MINIMAL_MEMORY_MAX_CHARS)
    }

    @Test
    fun resumoDaConversaChegaAoPromptMinimalista() {
        // Pista explícita do usuário ("Fechado") vira decisão sem tag do modelo.
        val history = listOf(
            ChatTurn(true, "Fechado, mensagem em 3 pontos."),
            ChatTurn(false, "Combinado! Vamos desenvolver o primeiro."),
        )
        val resumo = renderMemoryBlock(buildRollingSummary(history, ""))
        assertTrue(resumo != null)
        val prefixo = minimalMemoryPrefix(resumo)
        assertTrue(prefixo.contains("MEMÓRIA DA CONSTRUÇÃO"))
        val (_, user) = buildMinimalPastePrompts(esboco, prefixo + "continue", history)
        assertTrue(user.contains("MEMÓRIA DA CONSTRUÇÃO"))
    }

    // ---------- T1 (Fase 2.2): esboço colado no contexto do "insira tudo" ----------

    @Test
    fun applyStructureSemEsbocoMantemInstrucaoPura() {
        assertEquals(APPLY_STRUCTURE_INSTRUCTION, applyStructureMessage(null))
        assertEquals(APPLY_STRUCTURE_INSTRUCTION, applyStructureMessage("   "))
        // Fallback do modo estruturado: instrução canônica + sem placeholders.
        assertTrue(APPLY_STRUCTURE_INSTRUCTION.contains("sem placeholders"))
        assertTrue(APPLY_STRUCTURE_INSTRUCTION.contains("# INTRODUÇÃO"))
        assertTrue(APPLY_STRUCTURE_INSTRUCTION.contains("# SEÇÃO 1"))
        assertTrue(APPLY_STRUCTURE_INSTRUCTION.contains("# CONCLUSÃO"))
    }

    @Test
    fun applyStructureComEsbocoIncluiBloco() {
        val msg = applyStructureMessage("Esboço sobre esperança. Ponto 1: confiar.")
        assertTrue(msg.startsWith(APPLY_STRUCTURE_INSTRUCTION))
        assertTrue(msg.contains("## ESBOÇO ORIGINAL (colado pelo usuário)"))
        assertTrue(msg.contains("Esboço sobre esperança. Ponto 1: confiar."))
        assertTrue(msg.contains("## FIM DO ESBOÇO ORIGINAL"))
    }

    @Test
    fun applyStructureTruncaEsbocoNoTeto() {
        val grande = "x".repeat(MINIMAL_PASTE_MAX_CHARS + 500)
        val msg = applyStructureMessage(grande)
        assertTrue(msg.contains("x".repeat(MINIMAL_PASTE_MAX_CHARS)))
        assertFalse(msg.contains("x".repeat(MINIMAL_PASTE_MAX_CHARS + 1)))
    }

    // ---------- Fix: ApplyStructure sem frases-guia meta ----------

    @Test
    fun applyStructureOrientaSomenteTextoFalavel() {
        assertTrue(
            APPLY_STRUCTURE_INSTRUCTION.contains("APENAS o texto que o orador deve falar")
        )
        assertTrue(APPLY_STRUCTURE_INSTRUCTION.contains("NÃO inclua instruções meta"))
        assertTrue(APPLY_STRUCTURE_INSTRUCTION.contains("\"Abra com…\""))
        // O resto do contrato segue: sem placeholders + formato canônico.
        assertTrue(APPLY_STRUCTURE_INSTRUCTION.contains("sem placeholders"))
        assertTrue(APPLY_STRUCTURE_INSTRUCTION.contains("# SEÇÃO 1"))
    }

    @Test
    fun applyStructureComEsbocoMantemGuiaDeTextoFalavel() {
        val msg = applyStructureMessage("Esboço de teste com três seções.")
        assertTrue(msg.contains("## ESBOÇO ORIGINAL (colado pelo usuário)"))
        assertTrue(msg.contains("APENAS o texto que o orador deve falar"))
        assertTrue(msg.contains("NÃO inclua instruções meta"))
    }

    // ---------- T2 (Fase 2.2): modo criação explícito no system minimalista ----------

    @Test
    fun modoCriacaoExplicitoNoSystemMinimalista() {
        val (system, _) = buildMinimalPastePrompts(esboco, "e agora?", emptyList())
        assertTrue(system.contains("〈sugestão〉"))
        // Makeover: a redação virou imperativo ("é permitido" → "marque com").
        assertTrue(system.contains("conteúdo novo"))
        assertTrue(system.contains("sugestão sua"))
        assertTrue(system.contains(INSUFFICIENT_EVIDENCE_MESSAGE))
    }

    @Test
    fun insuficienciaFicaRestritaAFatosNumerosEReferencias() {
        val (system, _) = buildMinimalPastePrompts(esboco, "e agora?", emptyList())
        assertTrue(system.contains("fatos, números ou referências"))
        // O guia de linguagem entra de propósito; o que não pode voltar é a
        // cauda de chat, as regras do S-34 ou o contexto de dossiê.
        assertTrue(system.contains("DIRETRIZES DE LINGUAGEM"))
        assertFalse(system.contains("Abra com 1 frase"))
        assertFalse(system.contains("REGRAS DO S-34"))
        assertFalse(system.contains("CONTEXTO DO DOSSIÊ"))
    }
}
