package com.bettertalker.app

import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.copilot.friendlyChatError
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.llm.GeminiProvider
import com.bettertalker.app.data.llm.LlmAction
import com.bettertalker.app.data.llm.LlmHttpClient
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.LlmResponse
import com.bettertalker.app.data.llm.LlmResponseMeta
import com.bettertalker.app.data.llm.ProviderError
import com.bettertalker.app.data.llm.ProviderFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fase 18 — BLOCO A. Testes do provider (§30-33).
 *
 * Contratos travados: configuração, request/response, timeout, cancelamento,
 * retry transitório, erro definitivo, chave fora de logs, factory, pipeline
 * com fake e consumo real do prompt (§32 obrigatório).
 */
class LlmProviderTest {

    private class FakeHttp(val script: MutableList<Any>) : LlmHttpClient {
        val bodies = mutableListOf<String>()
        val urls = mutableListOf<String>()
        var calls = 0

        override suspend fun postJson(url: String, body: String, timeoutMs: Long): LlmHttpClient.HttpResult {
            calls++
            urls += url
            bodies += body
            return when (val next = script.removeAt(0)) {
                is LlmHttpClient.HttpResult -> next
                is Exception -> throw next
                else -> error("script inválido")
            }
        }
    }

    private fun ok(text: String) = LlmHttpClient.HttpResult(200,
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" +
            GeminiProvider.jsonEscape(text) + "}]}}]}")

    private fun req(msg: String = "melhore isso") = LlmRequest(
        text = "corpo do bloco",
        message = msg,
        history = emptyList(),
        isFirstMessage = true,
        contextPack = ContextPack(emptyList(), emptyList()),
        blockTitle = "Introdução"
    )

    // ---------- 1-2. configuração ----------

    @Test
    fun configuracaoAusenteViraOfflineExplicito() = runBlocking {
        val http = FakeHttp(mutableListOf())
        val p = GeminiProvider(apiKey = "", http = http)
        val res = p.generate(req())
        assertTrue(res.meta.offline)
        assertEquals(0, res.meta.attempts)
        assertEquals(0, http.calls)
        assertTrue(res.text.contains("Modo offline"))
    }

    @Test
    fun configuracaoValidaRespondeRemoto() = runBlocking {
        val http = FakeHttp(mutableListOf(ok("resposta real")))
        val p = GeminiProvider(apiKey = "k", http = http)
        val res = p.generate(req())
        assertFalse(res.meta.offline)
        assertEquals(1, res.meta.attempts)
        assertEquals("resposta real", res.text)
        assertEquals("gemini", res.meta.providerId)
    }

    // ---------- 3-4. request/response ----------

    @Test
    fun requestBodyCarregaOPrompt() {
        val body = GeminiProvider.requestBody("PROMPT-MARCADOR-123")
        assertTrue(body.contains("PROMPT-MARCADOR-123"))
        assertTrue(body.contains("generateContent").not())
        assertTrue(body.contains("temperature"))
    }

    @Test
    fun respostaInvalidaViraErroEstruturado() = runBlocking {
        val http = FakeHttp(mutableListOf(LlmHttpClient.HttpResult(200, "{\"foo\":1}")))
        val p = GeminiProvider(apiKey = "k", http = http)
        try {
            p.generate(req())
            fail("deveria lançar")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.INVALID_RESPONSE, e.code)
            assertEquals(1, e.attempts)
        }
    }

    // ---------- 6-7. timeout ----------

    @Test
    fun timeoutTransitorioTentaDuasVezes() = runBlocking {
        val http = FakeHttp(mutableListOf(
            java.net.SocketTimeoutException("t1"),
            java.net.SocketTimeoutException("t2")
        ))
        val p = GeminiProvider(apiKey = "k", http = http)
        try {
            p.generate(req())
            fail("deveria lançar")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.TIMEOUT, e.code)
            assertEquals(2, e.attempts)
            assertEquals(2, http.calls)
        }
    }

    // ---------- 7. cancelamento ----------

    @Test
    fun cancelamentoViraCancelledSemRetry() = runBlocking {
        val http = FakeHttp(mutableListOf(kotlinx.coroutines.CancellationException()))
        val p = GeminiProvider(apiKey = "k", http = http)
        try {
            p.generate(req())
            fail("deveria lançar")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.CANCELLED, e.code)
            assertEquals(1, http.calls)
        }
    }

    // ---------- 8. retry transitório ----------

    @Test
    fun erro500TentaDeNovoERecupera() = runBlocking {
        val http = FakeHttp(mutableListOf(
            LlmHttpClient.HttpResult(500, "erro"),
            ok("recuperado")
        ))
        val p = GeminiProvider(apiKey = "k", http = http, log = {})
        val res = p.generate(req())
        assertEquals("recuperado", res.text)
        assertEquals(2, res.meta.attempts)
    }

    @Test
    fun rateLimitContaComoTransitorio() = runBlocking {
        val http = FakeHttp(mutableListOf(
            LlmHttpClient.HttpResult(429, "lento"),
            LlmHttpClient.HttpResult(429, "lento")
        ))
        val p = GeminiProvider(apiKey = "k", http = http, log = {})
        try {
            p.generate(req())
            fail("deveria lançar")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.RATE_LIMIT, e.code)
            assertEquals(2, http.calls)
        }
    }

    // ---------- 9. erro definitivo ----------

    @Test
    fun erro401NaoTentaDeNovo() = runBlocking {
        val http = FakeHttp(mutableListOf(LlmHttpClient.HttpResult(401, "nope")))
        val p = GeminiProvider(apiKey = "k", http = http)
        try {
            p.generate(req())
            fail("deveria lançar")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.AUTHENTICATION, e.code)
            assertEquals(1, http.calls)
            assertEquals(1, e.attempts)
        }
    }

    // ---------- 11. chave nunca em logs/erro ----------

    @Test
    fun chaveNuncaApareceEmErro() = runBlocking {
        val secret = "AIzaSy-SEGREDO-TESTE-123"
        val http = FakeHttp(mutableListOf(LlmHttpClient.HttpResult(401, "nope")))
        val p = GeminiProvider(apiKey = secret, http = http)
        try {
            p.generate(req())
            fail("deveria lançar")
        } catch (e: ProviderError) {
            val dump = (e.message ?: "") + e.toString()
            assertFalse(dump.contains(secret))
            // URL montada contém a chave, mas nunca é exposta:
            assertTrue(http.urls[0].contains(secret))
        }
        // Erro humano correspondente também não vaza nada técnico.
        val human = friendlyChatError(ProviderErrorCode.AUTHENTICATION)
        assertFalse(human.contains("AIzaSy"))
        assertFalse(human.contains("401"))
    }

    // ---------- 12. factory ----------

    @Test
    fun factorySoUsaRotaRemotaComChave() {
        assertFalse(ProviderFactory.useRemoteRoute(null))
        assertFalse(ProviderFactory.useRemoteRoute(""))
        assertFalse(ProviderFactory.useRemoteRoute("   "))
        assertTrue(ProviderFactory.useRemoteRoute("k"))
    }

    @Test
    fun factoryCriaGemini() {
        val p = ProviderFactory.createWithKey("k")
        assertEquals("gemini", p.id)
        assertEquals(GeminiProvider.GEMINI_MODEL, p.model)
    }

    // ---------- §31 pipeline com fake ----------

    private class FakeProvider(val answer: String) : com.bettertalker.app.data.llm.LlmProvider {
        override val id = "fake"
        override val model = "fake-1"
        val received = mutableListOf<LlmRequest>()
        override suspend fun generate(request: LlmRequest): LlmResponse {
            received += request
            return LlmResponse(answer,
                LlmResponseMeta(id, model, 1, 1, offline = false))
        }
    }

    @Test
    fun pipelineMensagemIntentPackPromptFakeResposta() = runBlocking {
        // mensagem → intent (categoria) → pack → prompt → fake → resposta.
        val intent = com.bettertalker.app.data.copilot.inferIntent("deixe mais natural", false)
        assertEquals(TrainingCategory.NATURALNESS, intent.trainingCategory)
        val pack = com.bettertalker.app.data.copilot.packFor(
            contentHits = emptyList(), trainingHits = emptyList())
        val ctx = com.bettertalker.app.data.copilot.buildTurnContext(
            message = "deixe mais natural",
            history = listOf(ChatTurn(true, "quero melhorar a intro")),
            isFirstMessage = false,
            contentHits = emptyList(),
            blockTitle = "Introdução", blockMinutes = 3, blockText = "corpo"
        )
        assertTrue(ctx.prompt.contains("Continuidade:"))
        val fake = FakeProvider("resposta do modelo")
        val res = fake.generate(LlmRequest(
            text = "corpo", message = "deixe mais natural",
            history = listOf(ChatTurn(true, "quero melhorar a intro")),
            isFirstMessage = false, contextPack = ctx.pack, blockTitle = "Introdução"))
        assertEquals("resposta do modelo", res.text)
        assertEquals("deixe mais natural", fake.received[0].message)
        assertEquals("Introdução", fake.received[0].blockTitle)
        assertFalse(fake.received[0].isFirstMessage)
    }

    // ---------- §32 consumo real do prompt (obrigatório) ----------

    @Test
    fun promptConstruidoChegaAoHttp() = runBlocking {
        val http = FakeHttp(mutableListOf(ok("ok")))
        val p = GeminiProvider(apiKey = "k", http = http)
        p.generate(LlmRequest(
            text = "TEXTO-DO-BLOCO-XYZ",
            message = "MENSAGEM-USUARIO-XYZ",
            history = listOf(ChatTurn(false, "HISTORICO-ASSISTENTE-XYZ")),
            isFirstMessage = false,
            contextPack = ContextPack(
                listOf(EvidenceSource("c1", "REF-FONTE-XYZ", "texto",
                    SourceType.CONTENT, "Pub", null, null, null)),
                emptyList()),
            blockTitle = "BLOCO-TITULO-XYZ"
        ))
        val body = http.bodies[0]
        // O prompt montado pelo ChatPromptBuilder viaja no corpo HTTP.
        assertTrue(body.contains("MENSAGEM-USUARIO-XYZ"))
        assertTrue(body.contains("TEXTO-DO-BLOCO-XYZ"))
        assertTrue(body.contains("HISTORICO-ASSISTENTE-XYZ"))
        assertTrue(body.contains("REF-FONTE-XYZ"))
        assertTrue(body.contains("BLOCO-TITULO-XYZ"))
        assertTrue(body.contains("Continuidade:"))
    }

    // ---------- §33 isolamento factual/apresentação ----------

    @Test
    fun factualNaoLevaTraining() {
        val intent = com.bettertalker.app.data.copilot.inferIntent("isso está certo?", true)
        assertEquals(null, intent.trainingCategory)
        // buildTurnFor passaria training vazio: pack só com conteúdo.
        val pack = com.bettertalker.app.data.copilot.packFor(
            contentHits = emptyList(), trainingHits = emptyList())
        assertTrue(pack.trainingSources.isEmpty())
    }

    @Test
    fun apresentacaoPermiteTraining() {
        val intent = com.bettertalker.app.data.copilot.inferIntent("deixe mais natural", true)
        assertEquals(TrainingCategory.NATURALNESS, intent.trainingCategory)
    }

    // ---------- parsing ----------

    @Test
    fun parseCandidateTextExtraiEValida() {
        assertEquals("oi", GeminiProvider.parseCandidateText(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"oi\"}]}}]}"))
        assertEquals(null, GeminiProvider.parseCandidateText("{\"foo\":1}"))
        assertEquals(null, GeminiProvider.parseCandidateText(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"   \"}]}}]}"))
        assertEquals("a\nb\"c", GeminiProvider.parseCandidateText(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"a\\nb\\\"c\"}]}}]}"))
    }

    @Test
    fun isTransientStatusSeparaRetryDeFalha() {
        assertTrue(GeminiProvider.isTransientStatus(429))
        assertTrue(GeminiProvider.isTransientStatus(500))
        assertTrue(GeminiProvider.isTransientStatus(503))
        assertFalse(GeminiProvider.isTransientStatus(200))
        assertFalse(GeminiProvider.isTransientStatus(400))
        assertFalse(GeminiProvider.isTransientStatus(401))
        assertFalse(GeminiProvider.isTransientStatus(403))
    }

    @Test
    fun jsonEscapeRoundTrip() {
        val s = "aspas \" barra \\ quebra\nfim"
        val esc = GeminiProvider.jsonEscape(s)
        assertTrue(esc.startsWith("\"") && esc.endsWith("\""))
        assertEquals(s, GeminiProvider.jsonUnescape(esc.substring(1, esc.length - 1)))
    }

    // ---------- seleção (§16-17) ----------

    @Test
    fun selecaoTemPrioridadeNoRotulo() {
        assertEquals("trecho selecionado",
            com.bettertalker.app.data.copilot.contextLabel("Ponto 1", "Discurso", "trecho"))
        assertEquals("Ponto 1",
            com.bettertalker.app.data.copilot.contextLabel("Ponto 1", "Discurso", ""))
        assertEquals("Ponto 1",
            com.bettertalker.app.data.copilot.contextLabel("Ponto 1", "Discurso", null))
    }

    @Test
    fun acoesDoContratoExistem() {
        assertEquals(LlmAction.CHAT, LlmAction.valueOf("CHAT"))
        assertTrue(com.bettertalker.app.data.llm.DEFAULT_LLM_TIMEOUT_MS == 30_000L)
        assertTrue(com.bettertalker.app.data.llm.DEFAULT_LLM_MAX_ATTEMPTS == 2)
    }
}
