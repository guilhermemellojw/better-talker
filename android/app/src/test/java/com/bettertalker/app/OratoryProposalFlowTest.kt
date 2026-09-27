package com.bettertalker.app

import com.bettertalker.app.data.copilot.OratoryGeneration
import com.bettertalker.app.data.copilot.OratoryGeneration.Action
import com.bettertalker.app.data.copilot.OratoryGeneration.Mode
import com.bettertalker.app.data.copilot.OratoryGeneration.Result
import com.bettertalker.app.data.edit.ApplyResult
import com.bettertalker.app.data.edit.EditBlock
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.edit.ParseResult
import com.bettertalker.app.data.edit.applyEditProposal
import com.bettertalker.app.data.edit.captureBaseHashes
import com.bettertalker.app.data.edit.parseEditProposal
import com.bettertalker.app.data.llm.GeminiProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34StructuralRetrieval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 20-B — a geração oratória entra no MESMO pipeline F5:
 * spec → prompt especializado → provider → JSON → parse → proposta com
 * baseHash → aceitar/rejeitar. §22/§34.14-15.
 */
class OratoryProposalFlowTest {

    private val doc = S34Parser.parseS34(S34Fixture.TEXT)
    private val view2 = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!

    private fun spec(mode: Mode, action: Action = Action.INSERT) =
        (OratoryGeneration.spec(mode, doc, view2, action) as Result.Ready).spec

    /** Resposta sintética do "modelo": JSON em cerca, como o prompt pede. */
    private fun oratoryResponse(texto: String, replace: Boolean = false): String {
        val op = if (replace) {
            "{\"type\": \"replace\", \"content\": \"<p>$texto</p>\"}"
        } else {
            "{\"type\": \"insert\", \"position\": \"after\", \"content\": \"<p>$texto</p>\"}"
        }
        return "```json\n{\"explanation\": \"gerado\", \"operations\": [$op]}\n```"
    }

    @Test
    fun modoOratorioChegaAoProviderComoPromptEspecializado() {
        val http = LlmProviderTestSupport.okHttp()
        val provider = GeminiProvider(apiKey = "k", http = http, log = {})
        runBlocking {
            provider.generate(
                LlmRequest(
                    text = "texto do ponto 2",
                    message = "Desenvolva o ponto 2",
                    responseFormat = ResponseFormat.EDIT_PROPOSAL,
                    editMode = EditProposalMode.IMPROVE,
                    oratorySpec = spec(Mode.DEVELOPMENT)
                )
            )
        }
        val body = http.bodies.first()
        // O prompt especializado do modo viaja no corpo HTTP.
        assertTrue(body.contains("REGRAS DE GERAÇÃO ORATÓRIA"))
        assertTrue(body.contains("MODO: DESENVOLVIMENTO DO PONTO"))
        assertTrue(body.contains("ESTRUTURA DO S-34"))
        assertTrue(body.contains("Subponto 1"))
        assertTrue(body.contains("Tiago 2:17"))
        assertTrue(body.contains("[TRAINING]"))
        assertTrue(body.contains("Desenvolva o ponto 2"))
        // E NÃO usa o prompt genérico de edição.
        assertFalse(body.contains("PRESERVANDO todas as ideias originais"))
    }

    @Test
    fun respostaViraPropostaComBaseHashEAlvo() {
        val blocks = listOf(EditBlock("sec-2", "Leia Tiago 2:17."))
        val json = oratoryResponse("A fé cresce quando a colocamos em prática.", replace = true)
        val parsed = parseEditProposal(json, blocks, "sec-2", EditProposalMode.IMPROVE, "prop-orat")
        assertTrue(parsed is ParseResult.Ok)
        val proposal = (parsed as ParseResult.Ok).proposal
        assertEquals("prop-orat", proposal.id)
        assertEquals("sec-2", proposal.operations[0].targetId)
        // baseHash presente: stale detection continua funcionando.
        assertTrue(proposal.baseHashes.containsKey("sec-2"))
        assertEquals(captureBaseHashes(blocks, listOf("sec-2")), proposal.baseHashes)
        // Aplicar muda o bloco; rejeitar não muda nada (não aplicado).
        val applied = applyEditProposal(blocks, proposal)
        assertEquals(ApplyResult(com.bettertalker.app.data.edit.ProposalApplyStatus.APPLIED,
            listOf(EditBlock("sec-2", "<p>A fé cresce quando a colocamos em prática.</p>"))), applied)
        assertEquals("Leia Tiago 2:17.", blocks[0].contentHtml)
    }

    @Test
    fun propostaFicaStaleSeOBlocoMudar() {
        val json = oratoryResponse("Nova versão do ponto 2.")
        val parsed = parseEditProposal(json, listOf(EditBlock("sec-2", "original")),
            "sec-2", EditProposalMode.IMPROVE, "p1") as ParseResult.Ok
        val edited = listOf(EditBlock("sec-2", "usuário editou depois"))
        val r = applyEditProposal(edited, parsed.proposal)
        assertEquals(com.bettertalker.app.data.edit.ProposalApplyStatus.STALE_PROPOSAL, r.status)
        assertEquals("usuário editou depois", edited[0].contentHtml)
    }

    @Test
    fun modoReplaceUsaAcaoDeSubstituicaoNoPrompt() {
        val http = LlmProviderTestSupport.okHttp()
        val provider = GeminiProvider(apiKey = "k", http = http, log = {})
        runBlocking {
            provider.generate(
                LlmRequest(
                    text = "conteúdo atual",
                    message = "Melhore a introdução",
                    responseFormat = ResponseFormat.EDIT_PROPOSAL,
                    editMode = EditProposalMode.IMPROVE,
                    oratorySpec = spec(Mode.INTRODUCTION, Action.REPLACE)
                )
            )
        }
        val body = http.bodies.first()
        assertTrue(body.contains("MODO: ABERTURA"))
        assertTrue(body.contains("Para substituir conteúdo existente"))
        assertTrue(body.contains("Melhore a introdução"))
    }

    @Test
    fun geracaoOratoriaRespeitaIsolamentoNoPrompt() {
        val http = LlmProviderTestSupport.okHttp()
        val provider = GeminiProvider(apiKey = "k", http = http, log = {})
        runBlocking {
            provider.generate(
                LlmRequest(
                    text = "corpo",
                    message = "Desenvolva o ponto 2",
                    oratorySpec = spec(Mode.DEVELOPMENT)
                )
            )
        }
        val body = http.bodies.first()
        assertTrue(body.contains("Tiago 2:17"))
        assertFalse(body.contains("João 17:17"))
        assertFalse(body.contains("Hebreus 10:23"))
        assertFalse(body.contains("w24.01"))
    }

    @Test
    fun semSpecOratorioPromptGenericoContinua() {
        val http = LlmProviderTestSupport.okHttp()
        val provider = GeminiProvider(apiKey = "k", http = http, log = {})
        runBlocking {
            provider.generate(
                LlmRequest(
                    text = "corpo",
                    message = "melhore",
                    responseFormat = ResponseFormat.EDIT_PROPOSAL,
                    editMode = EditProposalMode.IMPROVE
                )
            )
        }
        val body = http.bodies.first()
        assertFalse(body.contains("REGRAS DE GERAÇÃO ORATÓRIA"))
        assertTrue(body.contains("PRESERVANDO todas as ideias originais"))
    }
}
