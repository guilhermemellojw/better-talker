package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.generationTelemetry
import com.bettertalker.app.data.planning.looksTruncated
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 — `finish_reason=length` nunca vira sucesso silencioso: o provider sobe
 * o orçamento até o cap do caminho; no cap, devolve o corte sinalizado para o
 * chamador marcar `possiblyTruncated`/“Continuar gerando”. Fake HTTP, sem rede.
 */
class TruncationRetryTest {

    private class Scripted(
        val result: LlmHttpClient.HttpResult,
        val lines: List<String> = emptyList(),
    )

    private class FakeStream(val script: MutableList<Any>) : DeepSeekStreamClient {
        val bodies = mutableListOf<String>()

        override suspend fun postStream(
            url: String,
            body: String,
            timeoutMs: Long,
            headers: Map<String, String>,
            onLine: (String) -> Unit,
        ): LlmHttpClient.HttpResult {
            bodies += body
            return when (val next = script.removeAt(0)) {
                is Scripted -> {
                    next.lines.forEach(onLine)
                    next.result
                }
                is LlmHttpClient.HttpResult -> next
                else -> error("script inválido")
            }
        }
    }

    private fun delta(text: String): String =
        "data: {\"choices\":[{\"delta\":{\"content\":${JsonCodec.escape(text)}}}]}"

    private fun stopStream(vararg parts: String) = Scripted(
        LlmHttpClient.HttpResult(200, ""),
        parts.map { delta(it) } + listOf(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ),
    )

    private fun lengthStream(vararg parts: String) = Scripted(
        LlmHttpClient.HttpResult(200, ""),
        parts.map { delta(it) } + listOf(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}",
            "data: [DONE]",
        ),
    )

    private fun provider(http: FakeStream) =
        DeepSeekProvider(apiKey = "sk-teste", stream = http, log = {})

    private fun request(
        maxOutputTokens: Int,
        cap: Int,
        maxAttempts: Int = 2,
    ) = LlmRequest(
        text = "",
        message = "continue",
        maxAttempts = maxAttempts,
        maxOutputTokens = maxOutputTokens,
        maxOutputTokensCap = cap,
    )

    @Test
    fun lengthComCapMaior_sobeOBudgetEBemSucede() = runBlocking {
        val http = FakeStream(mutableListOf(lengthStream("parcial"), stopStream("completo")))
        val res = provider(http).generate(request(maxOutputTokens = 1500, cap = 4096))

        assertEquals("completo", res.text)
        assertTrue(http.bodies[0].contains("\"max_tokens\":1500"))
        assertTrue(http.bodies[1].contains("\"max_tokens\":3000"))
        assertEquals(3000, res.meta.budget)
        assertEquals(2, res.meta.attempts)
        assertEquals(1, res.meta.retries)
        assertEquals("stop", res.meta.finishReason)
    }

    @Test
    fun lengthNoTeto_naoFazLoopEDevolveOCorteSinalizado() = runBlocking {
        val http = FakeStream(mutableListOf(lengthStream("parcial")))
        val res = provider(http).generate(request(maxOutputTokens = 4096, cap = 4096))

        assertEquals(1, http.bodies.size)
        assertEquals(4096, res.meta.budget)
        assertEquals(1, res.meta.attempts)
        assertEquals(0, res.meta.retries)
        assertEquals("length", res.meta.finishReason)
        assertTrue(looksTruncated(res.text, res.meta.finishReason))
    }

    @Test
    fun segundoLength_paraNoCapSemTerceiraChamada() = runBlocking {
        val http = FakeStream(
            mutableListOf(lengthStream("parte 1"), lengthStream("parte 2"))
        )
        val res = provider(http).generate(request(maxOutputTokens = 3000, cap = 4096))

        assertEquals(2, http.bodies.size)
        assertTrue(http.bodies[0].contains("\"max_tokens\":3000"))
        assertTrue(http.bodies[1].contains("\"max_tokens\":4096"))
        assertEquals(4096, res.meta.budget)
        assertEquals(2, res.meta.attempts)
        assertEquals("length", res.meta.finishReason)
    }

    @Test
    fun chatCurtoComStop_naoRegride() = runBlocking {
        val http = FakeStream(mutableListOf(stopStream("Resposta curta.")))
        val res = provider(http).generate(request(maxOutputTokens = 2048, cap = 4096))

        assertEquals("Resposta curta.", res.text)
        assertEquals(1, http.bodies.size)
        assertEquals(1, res.meta.attempts)
        assertEquals("stop", res.meta.finishReason)
    }

    @Test
    fun telemetriaContaRetries() {
        val t = generationTelemetry(
            route = "proposal",
            text = "{}",
            attempts = 3,
        )
        assertEquals(2, t.retries)
        assertTrue(t.line().contains("|retries=2"))
    }
}
