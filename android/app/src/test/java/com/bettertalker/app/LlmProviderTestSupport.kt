package com.bettertalker.app

import com.bettertalker.app.data.llm.DeepSeekStreamClient
import com.bettertalker.app.data.llm.LlmHttpClient

/** Fase 19-B.5 — apoio de teste: fake HTTP que captura o corpo enviado. */
object LlmProviderTestSupport {
    class CapturingHttp : LlmHttpClient {
        val bodies = mutableListOf<String>()
        override suspend fun postJson(
            url: String, body: String, timeoutMs: Long,
            headers: Map<String, String>
        ): LlmHttpClient.HttpResult {
            bodies += body
            return LlmHttpClient.HttpResult(
                200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"
            )
        }
    }

    fun okHttp(): CapturingHttp = CapturingHttp()

    /**
     * Stream SSE fake do DeepSeek (cleanup multi-LLM): captura os bodies e
     * responde 200 com um delta "ok" + stop + [DONE]. Substitui o antigo uso
     * do GeminiProvider como fixture de prompt nos testes.
     */
    class CapturingStream(
        private val extraLines: List<String> = emptyList(),
    ) : DeepSeekStreamClient {
        val bodies = mutableListOf<String>()
        val urls = mutableListOf<String>()
        var calls = 0
            private set

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
            val lines = listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
            ) + extraLines + listOf(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "data: [DONE]",
            )
            lines.forEach(onLine)
            return LlmHttpClient.HttpResult(200, "")
        }
    }
}
