package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2.4 — testes da política central de orçamento. Puro/JVM.
 *
 * `tok(n)` produz ~n tokens (4 chars/token, estimador do LeanRag).
 */
class LocalContextBudgetTest {

    private fun src(ref: String, text: String) = EvidenceSource(
        id = ref, reference = ref, text = text, sourceType = SourceType.CONTENT,
        publication = "w", section = null, paragraph = null, page = null,
    )

    /** ~n tokens no estimador chars/4. */
    private fun tok(n: Int) = "x".repeat(n * 4)

    private fun req(
        text: String = "",
        message: String = "pergunta curta",
        contextBlock: String? = null,
        pack: ContextPack? = null,
        history: List<ChatTurn> = emptyList(),
        maxOutputTokens: Int = 1000,
    ) = LlmRequest(
        text = text, message = message, contextBlock = contextBlock,
        contextPack = pack, history = history, maxOutputTokens = maxOutputTokens,
    )

    @Test
    fun smallContext_noTrimming() {
        val r = req(contextBlock = "## SEÇÃO ATUAL\nTítulo: X\n", message = "oi")
        val res = LocalContextBudget.apply(r)
        assertTrue(res.plan.trimmed.isEmpty())
        assertEquals(r.contextBlock, res.request.contextBlock)
        assertEquals(r.message, res.request.message)
        assertTrue(res.plan.fits)
    }

    @Test
    fun generationMarginRespected() {
        val r = req(message = tok(200), contextBlock = tok(5000))
        val res = LocalContextBudget.apply(r)
        val expectedBudget = LocalContextBudget.TOTAL_CONTEXT_TOKENS -
            1000 - LocalContextBudget.SAFETY_MARGIN_TOKENS
        assertEquals(expectedBudget, res.plan.budget)
        assertEquals(1000, res.plan.outputReserve)
        assertTrue(res.plan.trimmed.contains("topic"))
        assertTrue("inputTotal=${res.plan.inputTotal} budget=${res.plan.budget}",
            res.plan.inputTotal <= res.plan.budget)
        assertTrue(res.plan.fits)
    }

    @Test
    fun outputReserveFollowsRequestCap() {
        val res = LocalContextBudget.apply(req(message = "oi", maxOutputTokens = 700))
        assertEquals(700, res.plan.outputReserve)
        assertEquals(4096 - 700 - LocalContextBudget.SAFETY_MARGIN_TOKENS, res.plan.budget)
    }

    @Test
    fun messageNeverTrimmed_andFlaggedWhenAloneOverBudget() {
        val msg = tok(3000)
        val r = req(
            message = msg,
            contextBlock = tok(3000),
            history = List(20) { ChatTurn(false, tok(200)) },
        )
        val res = LocalContextBudget.apply(r)
        assertEquals(msg, res.request.message)
        assertTrue("mensagem sozinha acima do orçamento deve ser sinalizada", res.plan.userOverBudget)
    }

    @Test
    fun excessContext_trimsHistoryBeforeTopicAndRag() {
        val topic = "## SEÇÃO ATUAL\n" + tok(900)
        val pack = ContextPack(listOf(src("F1", tok(900))), emptyList())
        val history = List(30) { ChatTurn(it % 2 == 0, tok(300)) }
        val res = LocalContextBudget.apply(req(contextBlock = topic, pack = pack, history = history))

        assertTrue("histórico deve ser cortado", res.plan.trimmed.contains("history"))
        assertFalse("tópico não deve ser cortado antes do histórico", res.plan.trimmed.contains("topic"))
        assertTrue("RAG preservado", res.plan.rag > 0)
        assertTrue(res.plan.inputTotal <= res.plan.budget)
    }

    @Test
    fun ragPreservedOverHistory() {
        val pack = ContextPack(listOf(src("F1", tok(1000))), emptyList())
        val history = List(40) { ChatTurn(false, tok(300)) }
        val res = LocalContextBudget.apply(req(pack = pack, history = history))
        assertTrue("RAG deve sobrar", res.plan.rag > 0)
        assertTrue("RAG >= histórico", res.plan.history <= res.plan.rag)
    }

    @Test
    fun historyTrimKeepsMostRecent() {
        val history = (1..20).map { ChatTurn(it % 2 == 0, "turno $it " + tok(120)) }
        val res = LocalContextBudget.apply(req(message = "oi", history = history))
        assertTrue(res.plan.trimmed.contains("history"))
        assertTrue(res.request.history.size < history.size)
        assertTrue(
            "última troca deve ser a mais recente",
            res.request.history.last().text.startsWith("turno 20"),
        )
        assertFalse(
            "troca antiga não deve sobrar",
            res.request.history.any { it.text.startsWith("turno 1 ") },
        )
    }

    @Test
    fun topicTruncatedPreservesHead() {
        val topic = "## SEÇÃO ATUAL\nObjetivo: X\nAbordagem: Y\n" + tok(3000)
        val res = LocalContextBudget.apply(req(contextBlock = topic, message = "oi"))
        assertTrue(res.plan.trimmed.contains("topic"))
        assertTrue(res.request.contextBlock!!.startsWith("## SEÇÃO ATUAL"))
        assertTrue(res.request.contextBlock!!.contains("Objetivo: X"))
        assertTrue(res.request.contextBlock!!.endsWith(LocalContextBudget.TRUNCATION_MARKER))
    }

    @Test
    fun overviewTokens_detectsGlobalBlock() {
        val cb = "## SEÇÃO ATUAL\nTítulo: X\n\n" +
            "## ESTRUTURA DO DISCURSO\n- [BODY] A (5 min)\n- [BODY] B (5 min)\n\n" +
            "## TEXTOS BÍBLICOS\n- Jo 3:16: \"...\"\n"
        assertTrue(LocalContextBudget.overviewTokens(cb) > 0)
        assertEquals(0, LocalContextBudget.overviewTokens("## SEÇÃO ATUAL\nTítulo: X\n"))
    }

    @Test
    fun promptOverheadIsUpperBound() {
        val emptyPrompt = buildChatPrompt(
            message = "", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "",
        )
        val overhead = LeanRag.estimateTokens(emptyPrompt)
        assertTrue(
            "overhead real $overhead > constante ${LocalContextBudget.PROMPT_OVERHEAD_TOKENS}",
            overhead <= LocalContextBudget.PROMPT_OVERHEAD_TOKENS,
        )
    }

    @Test
    fun truncateToTokens_marksAndRespectsBudget() {
        val t = LocalContextBudget.truncateToTokens(tok(200), 10)
        assertTrue(t.endsWith(LocalContextBudget.TRUNCATION_MARKER))
        assertTrue(t.length <= 10 * LeanRag.CHARS_PER_TOKEN + LocalContextBudget.TRUNCATION_MARKER.length)
    }

    @Test
    fun bigRagStillFitsWithSmallMessage() {
        val pack = ContextPack(listOf(src("F1", tok(5000))), emptyList())
        val res = LocalContextBudget.apply(req(message = tok(100), pack = pack))
        assertTrue(res.plan.inputTotal <= res.plan.budget)
        assertTrue("RAG deve ter sobrado", res.plan.rag > 0)
        assertTrue(res.plan.trimmed.contains("rag"))
    }
}
