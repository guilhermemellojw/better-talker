package com.bettertalker.app

import com.bettertalker.app.data.copilot.OratoryGeneration
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.llm.GeminiProvider
import com.bettertalker.app.data.llm.LlmHttpClient
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ProviderFactory
import com.bettertalker.app.data.llm.QwenProvider
import com.bettertalker.app.data.llm.ResponseFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fase 20-F1 — transporte Groq/Qwen no Android (§§4,7-15).
 * Fake HTTP (sem rede na JVM); contrato espelhado do web qwenProvider.test.ts.
 */
class QwenGroqProviderTest {

    private class FakeHttp(val script: MutableList<Any>) : LlmHttpClient {
        val bodies = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        var calls = 0

        override suspend fun postJson(
            url: String, body: String, timeoutMs: Long,
            headers: Map<String, String>
        ): LlmHttpClient.HttpResult {
            calls++
            urls += url
            bodies += body
            this.headers += headers
            return when (val next = script.removeAt(0)) {
                is LlmHttpClient.HttpResult -> next
                is Exception -> throw next
                else -> error("script inválido")
            }
        }
    }

    private fun chatOk(content: String, usage: String = "") = LlmHttpClient.HttpResult(200,
        "{\"choices\":[{\"message\":{\"content\":" +
            GeminiProvider.jsonEscape(content) + "}}],$usage\"object\":\"chat.completion\"}",
        mapOf("x-ratelimit-remaining-requests" to "42"))

    private fun req() = LlmRequest(
        text = "corpo do bloco",
        message = "Desenvolva o ponto 2.",
        history = emptyList(),
        isFirstMessage = true,
        contextPack = ContextPack(emptyList(), emptyList()),
        responseFormat = ResponseFormat.EDIT_PROPOSAL,
        editMode = com.bettertalker.app.data.edit.EditProposalMode.INSERT,
        maxOutputTokens = 2048
    )

    private fun provider(http: FakeHttp) = QwenProvider(apiKey = "k-teste", http = http, log = {})

    // ---------- §4: modelo exato, sem fallback ----------

    @Test
    fun modeloExatoSemFallback() {
        assertEquals("qwen/qwen3.8-27b", QwenProvider.GROQ_MODEL)
        assertEquals("qwen/qwen3.8-27b", QwenProvider(apiKey = "k", http = FakeHttp(mutableListOf()), log = {}).model)
        assertEquals("qwen", QwenProvider(apiKey = "k", http = FakeHttp(mutableListOf()), log = {}).id)
    }

    // ---------- §7: endpoint + authorization ----------

    @Test
    fun endpointGroqEAuthorization() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("{\"explanation\":\"x\",\"operations\":[]}")))
        provider(http).generate(req())
        assertEquals("https://api.groq.com/openai/v1/chat/completions", http.urls.single())
        assertEquals("Bearer k-teste", http.headers.single()["Authorization"])
        assertTrue(http.bodies.single().contains("\"model\":\"qwen/qwen3.8-27b\""))
    }

    // ---------- §8: structured output estrito ----------

    @Test
    fun structuredOutputEstritoNoCaminhoDeProposta() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("{\"explanation\":\"x\",\"operations\":[]}")))
        provider(http).generate(req())
        val body = http.bodies.single()
        assertTrue(body.contains("\"response_format\""))
        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertTrue(body.contains("\"strict\":true"))
        assertTrue(body.contains("\"edit_proposal\""))
    }

    @Test
    fun chatLivreNaoPedeStructuredOutput() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("texto livre")))
        provider(http).generate(req().copy(
            responseFormat = ResponseFormat.TEXT, editMode = null))
        assertFalse(http.bodies.single().contains("response_format"))
    }

    // ---------- §9: reasoning low ----------

    @Test
    fun reasoningEffortLow() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("{\"explanation\":\"x\",\"operations\":[]}")))
        provider(http).generate(req())
        assertTrue(http.bodies.single().contains("\"reasoning_effort\":\"low\""))
    }

    // ---------- §10: paridade de prompt (oratorySpec) ----------

    @Test
    fun pedidoOratorioUsaOPromptEspecializado() = runBlocking {
        val doc = com.bettertalker.app.data.s34.S34Parser.parseS34(S34Fixture.TEXT)
        val view = com.bettertalker.app.data.s34.S34StructuralRetrieval
            .scopeToSection(doc, "sec-2")!!
        val spec = (OratoryGeneration.spec(
            OratoryGeneration.Mode.DEVELOPMENT, doc, view,
            OratoryGeneration.Action.INSERT
        ) as OratoryGeneration.Result.Ready).spec
        val http = FakeHttp(mutableListOf(chatOk("{\"explanation\":\"x\",\"operations\":[]}")))
        provider(http).generate(req().copy(oratorySpec = spec))
        val body = http.bodies.single()
        assertTrue(body.contains("MODO: DESENVOLVIMENTO DO PONTO"))
        assertTrue(body.contains("ESTRUTURA DO S-34"))
        assertTrue(body.contains("Tiago 2:17"))
        assertFalse(body.contains("Hebreus 10:23"))
    }

    // ---------- §12.7: timeout com retry ----------

    @Test
    fun timeoutTransitorioTentaDuasVezes() = runBlocking {
        val http = FakeHttp(mutableListOf(
            java.net.SocketTimeoutException("t"),
            chatOk("{\"explanation\":\"x\",\"operations\":[]}")
        ))
        val res = provider(http).generate(req().copy(maxAttempts = 2))
        assertEquals(2, http.calls)
        assertEquals(2, res.meta.attempts)
    }

    // ---------- §12.8: erro 4xx definitivo ----------

    @Test
    fun erro400NaoTentaDeNovo() = runBlocking {
        val http = FakeHttp(mutableListOf(LlmHttpClient.HttpResult(400, "bad")))
        try {
            provider(http).generate(req().copy(maxAttempts = 2))
            fail("deveria falhar")
        } catch (e: com.bettertalker.app.data.llm.ProviderError) {
            assertEquals(
                com.bettertalker.app.data.copilot.ProviderErrorCode.INVALID_REQUEST, e.code)
        }
        assertEquals(1, http.calls)
    }

    // ---------- §12.9: erro 5xx com retry ----------

    @Test
    fun erro500TentaDeNovoERecupera() = runBlocking {
        val http = FakeHttp(mutableListOf(
            LlmHttpClient.HttpResult(500, "x"),
            chatOk("{\"explanation\":\"x\",\"operations\":[]}")
        ))
        provider(http).generate(req().copy(maxAttempts = 2))
        assertEquals(2, http.calls)
    }

    // ---------- §12.10: rate limit ----------

    @Test
    fun rateLimitContaComoTransitorio() = runBlocking {
        val http = FakeHttp(mutableListOf(
            LlmHttpClient.HttpResult(429, "slow"),
            chatOk("{\"explanation\":\"x\",\"operations\":[]}")
        ))
        provider(http).generate(req().copy(maxAttempts = 2))
        assertEquals(2, http.calls)
    }

    // ---------- §12.11: resposta inválida ----------

    @Test
    fun choicesVazioViraErroEstruturado() = runBlocking {
        val http = FakeHttp(mutableListOf(LlmHttpClient.HttpResult(200, "{\"choices\":[]}")))
        try {
            provider(http).generate(req().copy(maxAttempts = 1))
            fail("deveria falhar")
        } catch (e: com.bettertalker.app.data.llm.ProviderError) {
            assertEquals(
                com.bettertalker.app.data.copilot.ProviderErrorCode.INVALID_RESPONSE, e.code)
        }
    }

    // ---------- §12.12: cancelamento ----------

    @Test
    fun cancelamentoPropagaSemRetry() = runBlocking {
        val http = FakeHttp(mutableListOf<Any>(kotlinx.coroutines.CancellationException("cancelled")))
        try {
            provider(http).generate(req().copy(maxAttempts = 2))
            fail("deveria falhar")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // esperado — cancelamento propaga, não vira ProviderError
        }
        assertEquals(1, http.calls)
    }

    // ---------- §13: sem chave, erro honesto ----------

    @Test
    fun semChaveErroHonestoSemFallbackFake() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("nunca")))
        try {
            QwenProvider(apiKey = "", http = http, log = {}).generate(req())
            fail("deveria falhar")
        } catch (e: com.bettertalker.app.data.llm.ProviderError) {
            assertEquals(
                com.bettertalker.app.data.copilot.ProviderErrorCode.UNAVAILABLE, e.code)
        }
        assertEquals(0, http.calls)
    }

    // ---------- §37: tokens e rate-limit observáveis ----------

    @Test
    fun tokensERateLimitObservaveis() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk(
            "{\"explanation\":\"x\",\"operations\":[]}",
            "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":300,\"total_tokens\":1500},"
        )))
        val res = provider(http).generate(req())
        assertEquals(1200, res.meta.usage?.inputTokens)
        assertEquals(300, res.meta.usage?.outputTokens)
        assertEquals(1500, res.meta.usage?.totalTokens)
        assertEquals("42", res.meta.rateLimit["x-ratelimit-remaining-requests"])
    }

    // ---------- factory: seleção sem fallback silencioso ----------

    @Test
    fun factoryCriaQwenComChaveGroq() {
        val p = ProviderFactory.createFor(ProviderFactory.RemoteConfig("qwen", "k"))
        assertTrue(p is QwenProvider)
        assertEquals("qwen/qwen3.8-27b", p.model)
    }

    @Test
    fun factoryCriaGeminiPorDefeito() {
        val p = ProviderFactory.createFor(ProviderFactory.RemoteConfig("gemini", "k"))
        assertTrue(p is GeminiProvider)
        val estranho = ProviderFactory.createFor(ProviderFactory.RemoteConfig("xyz", "k"))
        assertTrue(estranho is GeminiProvider)
    }

    // ---------- chave nunca em erro/log ----------

    @Test
    fun chaveNuncaApareceEmErro() = runBlocking {
        val http = FakeHttp(mutableListOf(LlmHttpClient.HttpResult(400, "bad")))
        try {
            QwenProvider(apiKey = "gsk-secreta", http = http, log = {}).generate(req())
            fail("deveria falhar")
        } catch (e: com.bettertalker.app.data.llm.ProviderError) {
            assertFalse((e.message ?: "").contains("gsk-secreta"))
            assertFalse(http.bodies.single().contains("gsk-secreta"))
        }
    }

    // ---------- F20-F1: S-34 vinculado entra nos candidatos ----------

    @Test
    fun candidatosS34CitadosPrimeiroVinculadosDepois() {
        assertEquals(
            listOf("a", "b"),
            com.bettertalker.app.ui.copilot.s34CandidateIds(listOf("a"), listOf("b"))
        )
        assertEquals(
            listOf("a", "b"),
            com.bettertalker.app.ui.copilot.s34CandidateIds(listOf("a", "b"), listOf("b", "a"))
        )
        assertEquals(
            emptyList<String>(),
            com.bettertalker.app.ui.copilot.s34CandidateIds(emptyList(), emptyList())
        )
    }

    // ---------- JSON_SCHEMA (Tarefa 2.2) ----------

    @Test
    fun jsonSchemaAnexadoQuandoInformado() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("{\"title\":\"t\"}")))
        val schema = "{\"type\":\"object\"}"
        provider(http).generate(
            req().copy(
                responseFormat = com.bettertalker.app.data.llm.ResponseFormat.JSON_SCHEMA,
                jsonSchema = schema,
            )
        )
        val body = http.bodies.single()
        assertTrue(body.contains("\"response_format\""))
        assertTrue(body.contains("\"json_schema\""))
        assertTrue(body.contains(schema))
    }

    @Test
    fun jsonSchemaAusenteNaoAnexaResponseFormat() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("{\"title\":\"t\"}")))
        provider(http).generate(
            req().copy(
                responseFormat = com.bettertalker.app.data.llm.ResponseFormat.JSON_SCHEMA,
                jsonSchema = null,
            )
        )
        val body = http.bodies.single()
        assertFalse(body.contains("response_format"))
    }

    @Test
    fun textNaoAnexaResponseFormat() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("texto livre")))
        provider(http).generate(
            req().copy(responseFormat = com.bettertalker.app.data.llm.ResponseFormat.TEXT)
        )
        val body = http.bodies.single()
        assertFalse(body.contains("response_format"))
    }

    @Test
    fun editProposalMantemSchemaProprio() = runBlocking {
        val http = FakeHttp(mutableListOf(chatOk("{\"explanation\":\"x\",\"operations\":[]}")))
        provider(http).generate(req())
        val body = http.bodies.single()
        assertTrue(body.contains(QwenProvider.EDIT_PROPOSAL_SCHEMA))
    }
}
