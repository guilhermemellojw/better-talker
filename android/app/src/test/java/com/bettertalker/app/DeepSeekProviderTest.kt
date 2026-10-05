package com.bettertalker.app

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.llm.DeepSeekProvider
import com.bettertalker.app.data.llm.DeepSeekStreamClient
import com.bettertalker.app.data.llm.GeminiProvider
import com.bettertalker.app.data.llm.LlmHttpClient
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ProviderError
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.data.llm.SseEvent
import com.bettertalker.app.data.edit.EditProposalMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * T1 — transporte DeepSeek com streaming SSE. Fake HTTP (sem rede na JVM).
 * Mesmo contrato de retry/erro do QwenProvider; streaming é o diferencial.
 */
class DeepSeekProviderTest {

    private class Scripted(
        val result: LlmHttpClient.HttpResult,
        val lines: List<String> = emptyList(),
        val throwAfter: Exception? = null,
    )

    private class FakeStream(val script: MutableList<Any>) : DeepSeekStreamClient {
        val bodies = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        var calls = 0

        override suspend fun postStream(
            url: String,
            body: String,
            timeoutMs: Long,
            headers: Map<String, String>,
            onLine: (String) -> Unit,
        ): LlmHttpClient.HttpResult {
            calls++
            urls += url
            bodies += body
            this.headers += headers
            return when (val next = script.removeAt(0)) {
                is Scripted -> {
                    next.lines.forEach(onLine)
                    next.throwAfter?.let { throw it }
                    next.result
                }
                is LlmHttpClient.HttpResult -> next
                is Exception -> throw next
                else -> error("script inválido")
            }
        }
    }

    private fun delta(text: String): String =
        "data: {\"choices\":[{\"delta\":{\"content\":${GeminiProvider.jsonEscape(text)}}}]}"

    private fun okStream(vararg parts: String) = Scripted(
        LlmHttpClient.HttpResult(200, "", mapOf("x-ratelimit-remaining-requests" to "9")),
        parts.map { delta(it) } + listOf(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ),
    )

    private fun provider(stream: FakeStream, key: String = "sk-teste") =
        DeepSeekProvider(apiKey = key, stream = stream, log = {})

    private fun req(
        message: String = "Olá",
        maxAttempts: Int = 2,
    ) = LlmRequest(
        text = "corpo do bloco",
        message = message,
        contextPack = ContextPack(emptyList(), emptyList()),
        maxAttempts = maxAttempts,
    )

    // ---------- streaming ----------

    @Test
    fun streamingSseAcumulaTextoEMeta() = runBlocking {
        val http = FakeStream(mutableListOf(okStream("Olá ", "mundo")))
        val res = provider(http).generate(req())
        assertEquals("Olá mundo", res.text)
        assertEquals("https://api.deepseek.com/v1/chat/completions", http.urls.single())
        assertEquals("Bearer sk-teste", http.headers.single()["Authorization"])
        assertEquals("text/event-stream", http.headers.single()["Accept"])
        val body = http.bodies.single()
        assertTrue(body.contains("\"model\":\"deepseek-flash\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"reasoning_effort\":\"low\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"temperature\":0.2"))
        assertEquals(1, res.meta.attempts)
        assertEquals("deepseek", res.meta.providerId)
        assertEquals("stop", res.meta.finishReason)
        assertEquals("9", res.meta.rateLimit["x-ratelimit-remaining-requests"])
    }

    @Test
    fun parseSseLinePuro() {
        assertEquals(SseEvent.Done, DeepSeekProvider.parseSseLine("data: [DONE]"))
        val d = DeepSeekProvider.parseSseLine(delta("oi"))
        assertTrue(d is SseEvent.Delta && d.text == "oi")
        assertNull(DeepSeekProvider.parseSseLine("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"))
        assertNull(DeepSeekProvider.parseSseLine(""))
    }

    // ---------- retry / erros ----------

    @Test
    fun retryEm429DepoisSucesso() = runBlocking {
        val http = FakeStream(mutableListOf(LlmHttpClient.HttpResult(429, "{\"error\":\"rate\"}"), okStream("ok")))
        val res = provider(http).generate(req())
        assertEquals("ok", res.text)
        assertEquals(2, res.meta.attempts)
        assertEquals(2, http.calls)
    }

    @Test
    fun retryEm5xxDepoisSucesso() = runBlocking {
        val http = FakeStream(mutableListOf(LlmHttpClient.HttpResult(503, ""), okStream("ok")))
        val res = provider(http).generate(req())
        assertEquals(2, res.meta.attempts)
    }

    @Test
    fun semRetryEm401() = runBlocking {
        val http = FakeStream(mutableListOf(LlmHttpClient.HttpResult(401, "{\"error\":\"auth\"}")))
        try {
            provider(http).generate(req())
            fail("esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(com.bettertalker.app.data.copilot.ProviderErrorCode.AUTHENTICATION, e.code)
        }
        assertEquals(1, http.calls)
    }

    @Test
    fun timeoutViraProviderError() = runBlocking {
        val http = FakeStream(mutableListOf(SocketTimeoutException("timeout")))
        try {
            provider(http).generate(req(maxAttempts = 1))
            fail("esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(com.bettertalker.app.data.copilot.ProviderErrorCode.TIMEOUT, e.code)
        }
    }

    @Test
    fun cancelamentoPropaga() = runBlocking {
        val http = FakeStream(mutableListOf(kotlinx.coroutines.CancellationException("cancelado")))
        try {
            provider(http).generate(req())
            fail("esperava CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // ok: cancelamento não vira erro tipado
        }
    }

    @Test
    fun erroMidStreamNaoRepete() = runBlocking {
        val http = FakeStream(
            mutableListOf(
                Scripted(
                    LlmHttpClient.HttpResult(200, ""),
                    lines = listOf(delta("parcial")),
                    throwAfter = SocketTimeoutException("caiu no meio"),
                )
            )
        )
        try {
            provider(http).generate(req())
            fail("esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(com.bettertalker.app.data.copilot.ProviderErrorCode.TIMEOUT, e.code)
        }
        assertEquals(1, http.calls)
    }

    @Test
    fun semChaveFalhaHonesto() = runBlocking {
        val http = FakeStream(mutableListOf())
        try {
            provider(http, key = "").generate(req())
            fail("esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(com.bettertalker.app.data.copilot.ProviderErrorCode.UNAVAILABLE, e.code)
        }
        assertEquals(0, http.calls)
    }

    @Test
    fun maxAttemptsRespeitado() = runBlocking {
        val http = FakeStream(mutableListOf(LlmHttpClient.HttpResult(503, ""), LlmHttpClient.HttpResult(503, "")))
        try {
            provider(http).generate(req(maxAttempts = 2))
            fail("esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(com.bettertalker.app.data.copilot.ProviderErrorCode.UNAVAILABLE, e.code)
            assertEquals(2, e.attempts)
        }
        assertEquals(2, http.calls)
    }

    // ---------- corpo / contrato ----------

    @Test
    fun proposalUsaSchemaEstritoEStream() {
        val body = DeepSeekProvider.requestBody(
            system = "s", user = "u", editProposal = true, reasoningEffort = "low"
        )
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_schema\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"strict\":true"))
    }

    @Test
    fun modeloProConfiguravel() = runBlocking {
        val http = FakeStream(mutableListOf(okStream("ok")))
        val p = DeepSeekProvider(apiKey = "k", model = DeepSeekProvider.MODEL_PRO, stream = http, log = {})
        p.generate(req())
        assertTrue(http.bodies.single().contains("\"model\":\"deepseek-v4-pro\""))
        assertFalse(http.bodies.single().contains("\"model\":\"deepseek-flash\""))
    }
}
