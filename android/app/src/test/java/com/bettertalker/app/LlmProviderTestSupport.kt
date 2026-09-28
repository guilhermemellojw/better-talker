package com.bettertalker.app

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
}
