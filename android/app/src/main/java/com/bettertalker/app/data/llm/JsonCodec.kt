package com.bettertalker.app.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Helpers JSON mínimos compartilhados pelos providers (extraídos do
 * providers. Sem dependência de org.json.
 * Puro/testável.
 */
object JsonCodec {

    fun escape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }

    fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/** Transporte real via HttpURLConnection (mesmo padrão do JwMediaApi). */
class UrlConnectionHttpClient : LlmHttpClient {
    private val lock = Mutex()

    override suspend fun postJson(
        url: String,
        body: String,
        timeoutMs: Long,
        headers: Map<String, String>
    ): LlmHttpClient.HttpResult {
        // Mutex: HttpURLConnection por chamada; serializa para evitar
        // entrelaçamento de streams em rajada (uma geração por vez de qualquer forma).
        // IO: HTTP NUNCA na Main — sem isto, chamadas vindas do editor
        // (draft/esboço) lançam NetworkOnMainThreadException (sem mensagem).
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    readTimeout = connectTimeout
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    for ((k, v) in headers) setRequestProperty(k, v)
                }
                try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    LlmHttpClient.HttpResult(status, text, rateLimitHeaders(conn.headerFields))
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    companion object {
        private val RATE_LIMIT_HEADERS = setOf(
            "x-ratelimit-remaining-requests",
            "x-ratelimit-remaining-tokens",
            "x-ratelimit-reset-requests",
            "x-ratelimit-reset-tokens",
            "retry-after"
        )

        /** Só headers de limite, chaves minúsculas — nunca Authorization. */
        fun rateLimitHeaders(fields: Map<String?, List<String>>): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for ((k, v) in fields) {
                val name = k?.lowercase() ?: continue
                if (name in RATE_LIMIT_HEADERS) v.firstOrNull()?.let { out[name] = it }
            }
            return out
        }
    }
}

/**
 * Schemas JSON compartilhados pelos providers (envelope estrito: name + strict).
 */
object LlmSchemas {
    const val EDIT_PROPOSAL_SCHEMA: String =
        "{\"name\":\"edit_proposal\",\"strict\":true,\"schema\":" +
            "{\"type\":\"object\",\"properties\":" +
            "{\"explanation\":{\"type\":\"string\"}," +
            "\"operations\":{\"type\":\"array\",\"minItems\":1,\"items\":" +
            "{\"type\":\"object\",\"properties\":" +
            "{\"type\":{\"type\":\"string\",\"enum\":[\"insert\",\"replace\"]}," +
            "\"position\":{\"type\":\"string\",\"enum\":[\"before\",\"after\"]}," +
            "\"content\":{\"type\":\"string\"}}," +
            "\"required\":[\"type\",\"position\",\"content\"]," +
            "\"additionalProperties\":false}}}," +
            "\"required\":[\"explanation\",\"operations\"]," +
            "\"additionalProperties\":false}}"
}
