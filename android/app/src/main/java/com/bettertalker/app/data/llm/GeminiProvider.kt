package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.buildEditProposalPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fase 18 — BLOCO A. Provider remoto Gemini.
 *
 * Referência comportamental: `geminiProvider.ts` do web. Mesmas regras:
 * system+user concatenados num único turno, temperature 0.2,
 * maxOutputTokens 1000, retry SÓ em transitório (429/5xx/rede),
 * nunca em definitivo (400/401/403), resposta validada antes de entregar,
 * sem chave => motor offline explícito (nunca fallback silencioso).
 *
 * Segurança: a chave nunca aparece em logs, mensagens de erro ou exceções.
 * JSON manual (sem org.json): org.json é framework Android e não existe nos
 * testes JVM — mesma convenção do ChatCodec.
 */
class GeminiProvider(
    private val apiKey: String,
    override val model: String = GEMINI_MODEL,
    private val timeoutMs: Long = DEFAULT_LLM_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_LLM_MAX_ATTEMPTS,
    private val http: LlmHttpClient = UrlConnectionHttpClient(),
    /**
     * Log técnico (status/tentativas, sem chave e sem conteúdo). Injetável
     * porque android.util.Log não existe nos testes JVM.
     */
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) }
) : LlmProvider {

    override val id: String = "gemini"

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val started = System.currentTimeMillis()
        // Sem chave => motor offline explícito (§12 web): honesto, marcado.
        if (apiKey.isBlank()) {
            return LlmResponse(
                text = offlineChatText(request),
                meta = LlmResponseMeta(id, "$model+offline",
                    System.currentTimeMillis() - started, 0, offline = true)
            )
        }
        // F20-B: modo oratório tem prompt especializado próprio (estrutura +
        // fontes + treinamento + instruções do modo).
        val basePrompt = if (request.oratorySpec != null) {
            com.bettertalker.app.data.copilot.OratoryGeneration.buildPrompt(
                request.oratorySpec,
                request.message.ifBlank { request.brief }
            )
        } else if (request.responseFormat == ResponseFormat.EDIT_PROPOSAL &&
            request.editMode != null
        ) {
            buildEditProposalPrompt(
                mode = request.editMode,
                text = request.text,
                pack = request.contextPack
                    ?: com.bettertalker.app.data.domain.ContextPack(emptyList(), emptyList()),
                blockTitle = request.blockTitle,
                blockMinutes = request.blockMinutes,
                brief = request.brief,
                legacyPassages = request.contextPassages
            )
        } else buildChatPrompt(
            message = request.message.ifBlank { request.text },
            history = request.history,
            isFirstMessage = request.isFirstMessage,
            pack = request.contextPack
                ?: com.bettertalker.app.data.domain.ContextPack(emptyList(), emptyList()),
            blockTitle = request.blockTitle,
            blockMinutes = request.blockMinutes,
            blockText = request.text,
            legacyPassages = request.contextPassages,
            structural = request.structural,
            oratory = request.oratory
        )
        // F2b: dossiê da seção em foco vai ANTES da pergunta/instruções.
        // requestBody fica intocado (testes o chamam direto).
        val prompt = request.contextBlock?.takeIf { it.isNotBlank() }?.let { block ->
            "## CONTEXTO DO DOSSIÊ (seção em foco no editor)\n$block\n\n$basePrompt"
        } ?: basePrompt
        val body = requestBody(prompt, request.maxOutputTokens)
        // Chave como query param (API Gemini); montada só aqui, nunca logada.
        val url = "$GEMINI_ENDPOINT/models/$model:generateContent?key=$apiKey"
        var attempts = 0
        var lastError: Exception? = null
        val tries = request.maxAttempts.coerceIn(1, 4)
        while (attempts < tries) {
            attempts++
            try {
                val res = http.postJson(url,
                    body, request.timeoutMs.takeIf { it > 0 } ?: timeoutMs)
                when {
                    res.status == 200 -> {
                        val text = parseCandidateText(res.body)
                            ?: throw ProviderError(ProviderErrorCode.INVALID_RESPONSE,
                                "Resposta do Gemini em formato inesperado.", id, attempts)
                        return LlmResponse(text, LlmResponseMeta(id, model,
                            System.currentTimeMillis() - started, attempts, offline = false,
                            finishReason = parseFinishReason(res.body)))
                    }
                    res.status == 429 -> {
                        log("gemini 429 attempt=$attempts")
                        lastError = ProviderError(ProviderErrorCode.RATE_LIMIT,
                            "Limite de requisições atingido.", id, attempts)
                    }
                    res.status >= 500 -> {
                        log("gemini ${res.status} attempt=$attempts")
                        lastError = ProviderError(ProviderErrorCode.UNAVAILABLE,
                            "Modelo remoto indisponível.", id, attempts)
                    }
                    res.status == 401 || res.status == 403 -> throw ProviderError(
                        ProviderErrorCode.AUTHENTICATION, "Chave de API inválida.", id, attempts)
                    else -> {
                        log("gemini ${res.status} attempt=$attempts body=${res.body.take(200)}")
                        throw ProviderError(
                            ProviderErrorCode.INVALID_REQUEST, "Requisição rejeitada.", id, attempts)
                    }
                }
            } catch (e: ProviderError) {
                // Definitivo (auth/invalid/request) ou inválido: sem retry.
                if (e.code == ProviderErrorCode.AUTHENTICATION ||
                    e.code == ProviderErrorCode.INVALID_REQUEST ||
                    e.code == ProviderErrorCode.INVALID_RESPONSE) throw e
                lastError = e
            } catch (e: java.net.SocketTimeoutException) {
                lastError = ProviderError(ProviderErrorCode.TIMEOUT,
                    "Tempo esgotado ao chamar o modelo.", id, attempts)
            } catch (e: java.io.IOException) {
                lastError = ProviderError(ProviderErrorCode.NETWORK,
                    "Sem conexão com o provedor.", id, attempts)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Structured concurrency: cancelamento deve propagar, não virar erro tipado.
                // Alinhado ao padrão da Tarefa 1.3b (OutlineProposer) e de S34ImportHook.kt / LlmService.kt.
                // O throw imediato também pula o delay e a próxima iteração do retry.
                throw e
            }
            if (attempts < tries) delay(RETRY_BACKOFF_MS * attempts)
        }
        throw lastError ?: ProviderError(ProviderErrorCode.UNAVAILABLE,
            "Modelo remoto indisponível.", id, attempts)
    }

    companion object {
        /**
         * Default vivo (verificado em 2026-09-26): o `gemini-2.5-flash` do web
         * foi aposentado para chaves novas (404); `gemini-3.8-flash` responde
         * mas oscila (503s + 18s de latência sob carga); `gemini-3.6-flash`
         * responde 200 em ~3s. Mesmos parâmetros de geração (temp 0.2, 1000
         * tokens) — paridade de comportamento, não de nome de modelo.
         */
        const val GEMINI_MODEL = "gemini-3.6-flash"
        const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"

        /** Corpo da requisição generateContent. Puro/testável. */
        fun requestBody(prompt: String, maxOutputTokens: Int = DEFAULT_LLM_MAX_OUTPUT_TOKENS): String =
            "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":" + jsonEscape(prompt) + "}]}]," +
                "\"generationConfig\":{\"temperature\":0.2,\"maxOutputTokens\":" + maxOutputTokens + "}}"

        /**
         * Extrai candidates[0].content.parts[0].text; null se ausente/vazio.
         * Puro/testável.
         */
        fun parseCandidateText(body: String): String? {
            // Primeiro "text" dentro de parts; unescape JSON mínimo.
            val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?: return null
            return jsonUnescape(m.groupValues[1]).ifBlank { null }
        }

        /**
         * Motivo de término (`candidates[0].finishReason`: "STOP"/
         * "MAX_TOKENS"/…). Normalizado para o vocabulário do Groq
         * ("MAX_TOKENS" vira "length"); demais valores vão crus.
         * Null quando ausente. Puro/testável.
         */
        fun parseFinishReason(body: String): String? {
            val m = Regex("\"finishReason\"\\s*:\\s*\"([^\"]+)\"").find(body)
                ?: return null
            val raw = m.groupValues[1].ifBlank { return null }
            return if (raw == "MAX_TOKENS") "length" else raw
        }

        /** Transitório = vale retry. Definitivo = falha imediata. Puro/testável. */
        fun isTransientStatus(status: Int): Boolean =
            status == 429 || status >= 500

        fun jsonEscape(s: String): String {
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

        fun jsonUnescape(s: String): String {
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
}

/** Resposta offline honesta: útil, sem fingir acesso remoto. */
private fun offlineChatText(request: LlmRequest): String {
    val about = (request.message.ifBlank { request.text }).take(120)
    return "Modo offline: não tenho acesso ao assistente remoto agora, mas posso ajudar localmente.\n\n" +
        "Sobre \"$about\": uma técnica de apresentação que costuma ajudar é reescrever em frases " +
        "curtas, com uma ideia por frase, e marcar onde fazer pausa. Configure a chave de IA na " +
        "tela Modelo IA para respostas do assistente remoto."
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
                    // F20-F1: Authorization do Groq/Qwen via header (nunca em log).
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
