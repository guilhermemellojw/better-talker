package com.bettertalker.app

import com.bettertalker.app.data.copilot.OratoryFidelityCheck
import com.bettertalker.app.data.copilot.OratoryGeneration
import com.bettertalker.app.data.copilot.OratorySession
import com.bettertalker.app.data.edit.ApplyResult
import com.bettertalker.app.data.edit.EditBlock
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.edit.ParseResult
import com.bettertalker.app.data.edit.ProposalApplyStatus
import com.bettertalker.app.data.edit.applyEditProposal
import com.bettertalker.app.data.edit.parseEditProposal
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.QwenProvider
import com.bettertalker.app.data.llm.ResponseFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Fase 20-F1 §§17-21,37-38 — validação REAL dos 4 modos + iteração + F5
 * no transporte Groq/Qwen (JVM, HTTPS real).
 *
 * Só roda com GROQ_API_KEY válida no ambiente; sem ela, PULA (Assume).
 * Pacing de 75s entre chamadas (OTPM 1000/min do plano gratuito). Métricas
 * impressas no stdout ([F20-F1 real]); nenhuma falha é mascarada.
 */
class QwenGroqRealValidationTest {

    private data class CaseResult(
        val name: String,
        val ops: Int,
        val invented: List<String>,
        val leaked: List<String>,
        val unsupported: List<String>,
        val tokens: String
    )

    private val metrics = mutableMapOf(
        "realRuns" to 0, "wrongPoint" to 0, "wrongReference" to 0,
        "inventedReference" to 0, "crossPointLeak" to 0, "crossOutlineLeak" to 0,
        "unsupportedFact" to 0, "iterationWrongTarget" to 0, "jsonParseFailure" to 0
    )

    private fun bodyOf(proposal: com.bettertalker.app.data.edit.CopilotEditProposal): String =
        proposal.operations.mapNotNull {
            when (it) {
                is com.bettertalker.app.data.edit.EditOperation.Insert -> it.contentHtml
                is com.bettertalker.app.data.edit.EditOperation.Replace -> it.contentHtml
                else -> null
            }
        }.joinToString("\n\n").replace(Regex("<[^>]*>"), " ")

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun has(text: String, term: String) = norm(text).contains(norm(term))

    private fun run(
        provider: QwenProvider,
        doc: com.bettertalker.app.data.s34.S34Document,
        name: String,
        mode: OratoryGeneration.Mode,
        sectionId: String?,
        message: String,
        action: OratoryGeneration.Action = OratoryGeneration.Action.INSERT,
        own: List<String> = emptyList(),
        foreign: List<String> = emptyList()
    ): Pair<OratoryGeneration.Spec, String> {
        val view = sectionId?.let {
            com.bettertalker.app.data.s34.S34StructuralRetrieval.scopeToSection(doc, it)
        }
        val spec = (OratoryGeneration.spec(mode, doc, view, action)
            as OratoryGeneration.Result.Ready).spec
        val res = runBlocking {
            provider.generate(
                LlmRequest(
                    text = "corpo", message = message,
                    responseFormat = ResponseFormat.EDIT_PROPOSAL,
                    editMode = if (action == OratoryGeneration.Action.REPLACE)
                        EditProposalMode.IMPROVE else EditProposalMode.INSERT,
                    oratorySpec = spec,
                    maxOutputTokens = 1280, maxAttempts = 1
                )
            )
        }
        metrics["realRuns"] = metrics.getValue("realRuns") + 1
        val parsed = parseEditProposal(
            res.text, doc.sections.map { EditBlock(it.id, it.title) },
            spec.current?.sectionId ?: doc.sections.first().id,
            if (action == OratoryGeneration.Action.REPLACE)
                EditProposalMode.IMPROVE else EditProposalMode.INSERT,
            "prop-$name"
        )
        if (parsed !is ParseResult.Ok) {
            metrics["jsonParseFailure"] = metrics.getValue("jsonParseFailure") + 1
            throw AssertionError("$name: resposta real fora do schema")
        }
        val text = bodyOf(parsed.proposal)
        val report = OratoryFidelityCheck.check(text, spec)
        if (report.inventedReferences.isNotEmpty()) {
            metrics["inventedReference"] =
                metrics.getValue("inventedReference") + report.inventedReferences.size
        }
        if (report.leakedReferences.isNotEmpty()) {
            metrics["wrongReference"] =
                metrics.getValue("wrongReference") + report.leakedReferences.size
        }
        if (report.unsupportedNumbers.isNotEmpty()) {
            metrics["unsupportedFact"] =
                metrics.getValue("unsupportedFact") + report.unsupportedNumbers.size
        }
        var foreignHits = 0
        var ownHits = 0
        for (t in own) if (has(text, t)) ownHits++
        for (t in foreign) if (has(text, t)) {
            foreignHits++
            metrics["crossPointLeak"] = metrics.getValue("crossPointLeak") + 1
        }
        if (text.isNotBlank() && ownHits == 0 && foreignHits > 0) {
            metrics["wrongPoint"] = metrics.getValue("wrongPoint") + 1
        }
        println("[F20-F1 real] $name ops=${parsed.proposal.operations.size} " +
            "invented=${report.inventedReferences} leaked=${report.leakedReferences} " +
            "unsupported=${report.unsupportedNumbers} " +
            "usage=${res.meta.usage} rateLimit=${res.meta.rateLimit}")
        // Texto truncado para julgamento humano no relatório (fixture
        // sintética — sem conteúdo privado; nunca inclui chave).
        println("[F20-F1 texto] $name :: " + text.take(600).replace(Regex("\\s+"), " "))
        Thread.sleep(75_000)
        return spec to text
    }

    @Test
    fun quatroModosIteracaoEF5Reais() {
        val key = (System.getenv("GROQ_API_KEY") ?: "").trim()
        Assume.assumeTrue(
            "sem credencial Groq válida no ambiente — validação real pulada",
            key.startsWith("gsk_") && key.length > 20
        )
        val provider = QwenProvider(apiKey = key, log = {})
        assertEquals("qwen/qwen3.8-27b", provider.model)
        val doc = com.bettertalker.app.data.s34.S34Parser.parseS34(S34Fixture.TEXT)

        // §26 introduction
        val (introSpec, introText) = run(provider, doc, "introduction",
            OratoryGeneration.Mode.INTRODUCTION, "sec-1",
            "Crie uma introdução para este discurso.",
            own = listOf("introdução", "confiança", "ministério"),
            foreign = listOf("Tiago 2:17", "Hebreus 10:23"))

        // §28 development sec-2
        val (devSpec, devText) = run(provider, doc, "development-sec2",
            OratoryGeneration.Mode.DEVELOPMENT, "sec-2",
            "Desenvolva o ponto 2.",
            own = listOf("Tiago 2:17", "prática", "obras"),
            foreign = listOf("João 17:17", "Hebreus 10:23"))

        // §30 transition 2→3
        run(provider, doc, "transition-2-3",
            OratoryGeneration.Mode.TRANSITION, "sec-2",
            "Faça uma transição do ponto 2 para o ponto 3.",
            own = listOf("prática", "fortalec"),
            foreign = listOf("João 17:17"))

        // §32 conclusion
        run(provider, doc, "conclusion",
            OratoryGeneration.Mode.CONCLUSION, "sec-3",
            "Faça uma conclusão.",
            own = listOf("fortalec", "fé", "conclusão"),
            foreign = emptyList())

        // §31 iteração: "Deixe mais natural." herda introduction/sec-1
        val decision = OratorySession.decide(
            "Deixe mais natural.", doc, "sec-1",
            OratorySession.LastGeneration(OratoryGeneration.Mode.INTRODUCTION, "sec-1")
        ) as OratorySession.Decision.Generate
        assertEquals(OratoryGeneration.Mode.INTRODUCTION, decision.mode)
        assertEquals("sec-1", decision.sectionId)
        if (decision.mode != OratoryGeneration.Mode.INTRODUCTION ||
            decision.sectionId != "sec-1"
        ) {
            metrics["iterationWrongTarget"] = metrics.getValue("iterationWrongTarget") + 1
        }
        run(provider, doc, "iteration-natural",
            decision.mode, decision.sectionId,
            "Deixe mais natural.",
            action = OratoryGeneration.Action.REPLACE,
            own = listOf("confiança", "ministério"),
            foreign = listOf("Tiago 2:17", "Hebreus 10:23"))

        // §§18-20 F5 sobre proposta real (mesmo spec da introdução)
        val regen = runBlocking {
            provider.generate(
                LlmRequest(
                    text = "corpo original", message = "Crie uma introdução.",
                    responseFormat = ResponseFormat.EDIT_PROPOSAL,
                    editMode = EditProposalMode.INSERT,
                    oratorySpec = introSpec,
                    maxOutputTokens = 1280, maxAttempts = 1
                )
            )
        }
        metrics["realRuns"] = metrics.getValue("realRuns") + 1
        val blocks = listOf(EditBlock("sec-1", "corpo original"))
        val parsed = parseEditProposal(
            regen.text, blocks, "sec-1", EditProposalMode.INSERT, "prop-f5"
        )
        assertTrue("resposta F5 fora do schema", parsed is ParseResult.Ok)
        val proposal = (parsed as ParseResult.Ok).proposal
        assertTrue(proposal.baseHashes.containsKey("sec-1"))
        // accept: aplica e muda o bloco
        val applied = applyEditProposal(blocks, proposal)
        assertEquals(ProposalApplyStatus.APPLIED, applied.status)
        // reject: não aplicar => blocos intactos
        assertEquals("corpo original", blocks[0].contentHtml)
        // stale: edição manual depois da geração => STALE, sem sobrescrever
        val edited = listOf(EditBlock("sec-1", "usuário editou depois"))
        val stale = applyEditProposal(edited, proposal)
        assertEquals(ProposalApplyStatus.STALE_PROPOSAL, stale.status)
        assertEquals("usuário editou depois", edited[0].contentHtml)

        println("[F20-F1 real] metrics=$metrics")
        assertEquals(0, metrics.getValue("wrongPoint"))
        assertEquals(0, metrics.getValue("crossPointLeak"))
        assertEquals(0, metrics.getValue("jsonParseFailure"))
    }
}
