package com.bettertalker.app

import com.bettertalker.app.data.copilot.OratoryGeneration
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.QwenProvider
import com.bettertalker.app.data.llm.ResponseFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Fase 20-F1 §11 — smoke test REAL do transporte Groq/Qwen na JVM.
 *
 * Só roda com GROQ_API_KEY válida no ambiente (formato gsk_…); sem ela, o
 * teste é PULADO (Assume), nunca falha. Nenhuma chave em código/fixture/log:
 * só metadados (modelo, latência, tokens) são impressos.
 */
class QwenGroqRealSmokeTest {

    @Test
    fun smokeIntroducaoReal() = runBlocking {
        val key = (System.getenv("GROQ_API_KEY") ?: "").trim()
        Assume.assumeTrue(
            "sem credencial Groq válida no ambiente — smoke real pulado",
            key.startsWith("gsk_") && key.length > 20
        )
        val doc = com.bettertalker.app.data.s34.S34Parser.parseS34(S34Fixture.TEXT)
        val view = com.bettertalker.app.data.s34.S34StructuralRetrieval
            .scopeToSection(doc, "sec-1")!!
        val spec = (OratoryGeneration.spec(
            OratoryGeneration.Mode.INTRODUCTION, doc, view,
            OratoryGeneration.Action.INSERT
        ) as OratoryGeneration.Result.Ready).spec
        val provider = QwenProvider(apiKey = key, log = {})
        assertEquals("qwen/qwen3.8-27b", provider.model)
        val res = provider.generate(
            LlmRequest(
                text = "corpo",
                message = "Crie uma introdução para este discurso.",
                responseFormat = ResponseFormat.EDIT_PROPOSAL,
                editMode = EditProposalMode.INSERT,
                oratorySpec = spec,
                maxOutputTokens = 1280,
                maxAttempts = 1
            )
        )
        assertFalse(res.meta.offline)
        assertEquals("qwen/qwen3.8-27b", res.meta.model)
        println("[F20-F1 smoke] model=${res.meta.model} latencyMs=${res.meta.durationMs} " +
            "attempts=${res.meta.attempts} usage=${res.meta.usage} " +
            "rateLimit=${res.meta.rateLimit}")
        val blocks = listOf(com.bettertalker.app.data.edit.EditBlock("sec-1", "corpo"))
        when (val r = com.bettertalker.app.data.edit.parseEditProposal(
            res.text, blocks, "sec-1", EditProposalMode.INSERT
        )) {
            is com.bettertalker.app.data.edit.ParseResult.Ok -> {
                assertTrue(r.proposal.operations.isNotEmpty())
                assertTrue(r.proposal.baseHashes.containsKey("sec-1"))
                println("[F20-F1 smoke] parsed=EditProposal ops=${r.proposal.operations.size}")
            }
            is com.bettertalker.app.data.edit.ParseResult.Invalid ->
                throw AssertionError("resposta real fora do schema: ${r.reason}")
        }
    }
}
