package com.bettertalker.app.data.llm

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.domain.planning.LongTextKind
import com.bettertalker.app.domain.planning.LongTextRequest
import com.bettertalker.app.domain.planning.LongTextScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloco de geração longa: orçamento no corpo, thinking off, retry por length
 * até 8192 e fallback 8192 → 4096. Fake HTTP, sem rede.
 */
class LongTextProviderTest {

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

    private fun okStream(vararg parts: String) = Scripted(
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

    private fun longRequest(minutes: Int, kind: LongTextKind = LongTextKind.SPEECH) =
        LlmRequest(
            text = "corpo",
            message = "escreva o texto da seção",
            contextPack = ContextPack(emptyList(), emptyList()),
            maxAttempts = 1,
            longTextRequest = LongTextRequest(
                kind = kind,
                scope = LongTextScope.SECTION,
                minutes = minutes,
                sectionMinutes = minutes,
            ),
        )

    @Test
    fun textoLongo30min_usaBudget6050_eThinkingNone() = runBlocking {
        val http = FakeStream(mutableListOf(okStream("ok")))
        val res = provider(http).generate(longRequest(30))

        val body = http.bodies.single()
        assertTrue(body.contains("\"max_tokens\":6050"))
        assertTrue(body.contains("\"reasoning_effort\":\"none\""))
        assertEquals(6050, res.meta.budget)
        assertFalse(res.meta.thinking)
    }

    @Test
    fun textoLongoDemo4min_usaBudget2048() = runBlocking {
        val http = FakeStream(mutableListOf(okStream("ok")))
        provider(http).generate(longRequest(4, LongTextKind.DEMO))
        assertTrue(http.bodies.single().contains("\"max_tokens\":2048"))
    }

    @Test
    fun cortePorLength_sobeAte8192() = runBlocking {
        val http = FakeStream(
            mutableListOf(
                lengthStream("parcial"),
                okStream("completo"),
            )
        )
        val res = provider(http).generate(longRequest(30).copy(maxAttempts = 2))

        assertEquals("completo", res.text)
        assertTrue(http.bodies[0].contains("\"max_tokens\":6050"))
        assertTrue(http.bodies[1].contains("\"max_tokens\":8192"))
        assertEquals(8192, res.meta.budget)
    }

    @Test
    fun rejeicaoDe8192_caiPara4096_semConsumirTentativa() = runBlocking {
        val http = FakeStream(
            mutableListOf(
                lengthStream("parcial"),
                LlmHttpClient.HttpResult(
                    400,
                    "{\"error\":{\"message\":\"Invalid max_tokens: too large\"}}",
                ),
                okStream("ok"),
            )
        )
        val res = provider(http).generate(longRequest(30).copy(maxAttempts = 3))

        assertEquals("ok", res.text)
        assertTrue(http.bodies[0].contains("\"max_tokens\":6050"))
        assertTrue(http.bodies[1].contains("\"max_tokens\":8192"))
        assertTrue(http.bodies[2].contains("\"max_tokens\":4096"))
        // O fallback não consome tentativa transitória; a 3ª chamada conta
        // como a 2ª tentativa válida (a 1ª foi o corte por length).
        assertEquals(2, res.meta.attempts)
    }

    @Test
    fun cardCurtoComStop_refazComInstrucaoDeExtensao() = runBlocking {
        val shortCard = "<texto-do-card>" + "palavra ".repeat(500) + "</texto-do-card>"
        val longCard = "<texto-do-card>" + "palavra ".repeat(1100) + "</texto-do-card>"
        // Deltas pequenos, como no SSE real — um único delta gigante estoura
        // o regex do parser de linha.
        val http = FakeStream(
            mutableListOf(
                okStream(*shortCard.chunked(120).toTypedArray()),
                okStream(*longCard.chunked(120).toTypedArray()),
            )
        )
        val res = provider(http).generate(longRequest(10).copy(maxAttempts = 2))

        assertEquals(2, http.bodies.size)
        assertTrue(http.bodies[1].contains("CORREÇÃO DE EXTENSÃO"))
        assertTrue(http.bodies[1].contains("1300 palavras"))
        assertTrue(res.text.contains("palavra"))
        // O retry de extensão não consome tentativa transitória.
        assertEquals(1, res.meta.attempts)
    }

    @Test
    fun doisRetriesDeExtensao_usamExpansaoDirigidaEBudget4300() = runBlocking {
        val card500 = "<texto-do-card>" + "palavra ".repeat(500) + "</texto-do-card>"
        val card800 = "<texto-do-card>" + "palavra ".repeat(800) + "</texto-do-card>"
        val card1100 = "<texto-do-card>" + "palavra ".repeat(1100) + "</texto-do-card>"
        val http = FakeStream(
            mutableListOf(
                okStream(*card500.chunked(120).toTypedArray()),
                okStream(*card800.chunked(120).toTypedArray()),
                okStream(*card1100.chunked(120).toTypedArray()),
            )
        )
        val res = provider(http).generate(longRequest(10).copy(maxAttempts = 2))

        assertEquals(3, http.bodies.size)
        assertTrue(http.bodies[1].contains("CORREÇÃO DE EXTENSÃO"))
        assertTrue(http.bodies[2].contains("EXPANSÃO DIRIGIDA"))
        assertTrue(http.bodies[2].contains("\"max_tokens\":4300"))
        assertTrue(res.text.contains("palavra"))
    }

    @Test
    fun textoCurtoDepoisCortado_aindaTemTentativaPara4300() = runBlocking {
        val shortCard = "<texto-do-card>" + "palavra ".repeat(500) + "</texto-do-card>"
        val longCard = "<texto-do-card>" + "palavra ".repeat(1100) + "</texto-do-card>"
        val http = FakeStream(
            mutableListOf(
                okStream(*shortCard.chunked(120).toTypedArray()),
                lengthStream(*longCard.chunked(120).toTypedArray()),
                okStream(*longCard.chunked(120).toTypedArray()),
            )
        )
        val res = provider(http).generate(longRequest(10).copy(maxAttempts = 2))

        assertEquals(3, http.bodies.size)
        assertTrue(http.bodies[1].contains("CORREÇÃO DE EXTENSÃO"))
        assertTrue(http.bodies[1].contains("\"max_tokens\":2150"))
        assertTrue(http.bodies[2].contains("\"max_tokens\":4300"))
        assertTrue(res.text.contains("palavra"))
    }

    @Test
    fun isMaxTokensRejected_soAceitaMensagemEspecifica() {
        assertTrue(
            DeepSeekProvider.isMaxTokensRejected(
                "{\"error\":\"Invalid max_tokens: too large\"}"
            )
        )
        assertTrue(
            DeepSeekProvider.isMaxTokensRejected(
                "maximum output tokens exceeded"
            )
        )
        assertFalse(
            DeepSeekProvider.isMaxTokensRejected(
                "invalid api key"
            )
        )
    }
}
