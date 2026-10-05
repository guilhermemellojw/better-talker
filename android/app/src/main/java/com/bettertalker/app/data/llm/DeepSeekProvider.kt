package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ProviderErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * T1 — provider DeepSeek (BYOD) com streaming SSE.
 *
 * Endpoint OpenAI-compatible. System e user separados; thinking desligado por
 * padrão (`reasoning_effort=low`); temperature 0.2; retry SÓ transitório
 * (429/5xx/rede), nunca 400/401/403; cancelamento propagado.
 *
 * Streaming: cada delta SSE é publicado no canal de parciais já observado pela
 * UI ([LocalProgress]) e o texto final é devolvido em [LlmResponse], mantendo
 * o contrato do [LlmProvider]. O gate de alucinação do chat roda depois,
 * inalterado.
 *
 * A chave nunca aparece em log/erro/exceção. Sem chave => UNAVAILABLE honesto.
 */
class DeepSeekProvider(
    private val apiKey: String,
    override val model: String = MODEL_FLASH,
    private val endpoint: String = ENDPOINT,
    private val reasoningEffort: String = REASONING_EFFORT,
    private val timeoutMs: Long = DEFAULT_LLM_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_LLM_MAX_ATTEMPTS,
    private val stream: DeepSeekStreamClient = UrlConnectionDeepSeekStreamClient(),
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) },
) : LlmProvider {

    override val id: String = "deepseek"

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val started = System.currentTimeMillis()
        if (apiKey.isBlank()) {
            throw ProviderError(
                ProviderErrorCode.UNAVAILABLE,
                "Configure a chave do DeepSeek na tela Modelo IA.", id, 0
            )
        }
        val prompts = deepSeekPromptsFor(request)
        val wantProposal = request.responseFormat == ResponseFormat.EDIT_PROPOSAL
        val body = requestBody(
            model = model,
            system = prompts.first,
            user = prompts.second,
            maxOutputTokens = request.maxOutputTokens,
            editProposal = wantProposal,
            jsonSchema = if (request.responseFormat == ResponseFormat.JSON_SCHEMA) request.jsonSchema else null,
            reasoningEffort = reasoningEffort,
        )
        val url = "${endpoint.trimEnd('/')}/chat/completions"
        // Autorização e negociação do stream via header (nunca na URL, nunca em log).
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "text/event-stream",
        )
        var attempts = 0
        var lastError: Exception? = null
        val tries = request.maxAttempts.coerceIn(1, 4)
        while (attempts < tries) {
            attempts++
            val sb = StringBuilder()
            var streamStarted = false
            var usageJson: String? = null
            var finish: String? = null
            LocalProgress.reset()
            try {
                val res = withContext(Dispatchers.IO) {
                    stream.postStream(
                        url, body,
                        request.timeoutMs.takeIf { it > 0 } ?: timeoutMs,
                        headers
                    ) { line ->
                        when (val ev = parseSseLine(line)) {
                            is SseEvent.Delta -> {
                                if (!streamStarted) {
                                    streamStarted = true
                                    LocalProgress.set(LocalPhase.Generating)
                                }
                                sb.append(ev.text)
                                LocalProgress.appendPartial(ev.text)
                            }
                            SseEvent.Done -> Unit
                            null -> {
                                // Chunk de metadados (usage/finish_reason): guarda o último.
                                if (line.contains("\"usage\"")) {
                                    usageJson = line.removePrefix("data:").trim()
                                }
                                Regex("\"finish_reason\"\\s*:\\s*\"([^\"]+)\"")
                                    .find(line)?.let { finish = it.groupValues[1].ifBlank { null } }
                            }
                        }
                    }
                }
                when {
                    res.status == 200 -> {
                        val text = sb.toString()
                        if (text.isBlank()) {
                            throw ProviderError(
                                ProviderErrorCode.INVALID_RESPONSE,
                                "Resposta vazia do DeepSeek.", id, attempts
                            )
                        }
                        LocalProgress.set(LocalPhase.Done)
                        return LlmResponse(
                            text,
                            LlmResponseMeta(
                                providerId = id,
                                model = model,
                                durationMs = System.currentTimeMillis() - started,
                                attempts = attempts,
                                offline = false,
                                usage = parseUsage(usageJson ?: ""),
                                rateLimit = res.headers,
                                finishReason = finish,
                            )
                        )
                    }
                    res.status == 429 -> {
                        log("deepseek 429 attempt=$attempts")
                        lastError = ProviderError(
                            ProviderErrorCode.RATE_LIMIT,
                            "Limite de requisições atingido.", id, attempts
                        )
                    }
                    res.status >= 500 -> {
                        log("deepseek ${res.status} attempt=$attempts")
                        lastError = ProviderError(
                            ProviderErrorCode.UNAVAILABLE,
                            "Modelo remoto indisponível.", id, attempts
                        )
                    }
                    res.status == 401 || res.status == 403 -> throw ProviderError(
                        ProviderErrorCode.AUTHENTICATION, "Chave de API inválida.", id, attempts
                    )
                    else -> {
                        log("deepseek ${res.status} attempt=$attempts body=${res.body.take(200)}")
                        throw ProviderError(
                            ProviderErrorCode.INVALID_REQUEST, "Requisição rejeitada.", id, attempts
                        )
                    }
                }
            } catch (e: ProviderError) {
                if (e.code == ProviderErrorCode.AUTHENTICATION ||
                    e.code == ProviderErrorCode.INVALID_REQUEST ||
                    e.code == ProviderErrorCode.INVALID_RESPONSE
                ) {
                    LocalProgress.set(LocalPhase.Idle)
                    throw e
                }
                lastError = e
            } catch (e: java.net.SocketTimeoutException) {
                lastError = ProviderError(
                    ProviderErrorCode.TIMEOUT, "Tempo esgotado ao chamar o modelo.", id, attempts
                )
            } catch (e: java.io.IOException) {
                lastError = ProviderError(
                    ProviderErrorCode.NETWORK, "Sem conexão com o provedor.", id, attempts
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Structured concurrency: cancelamento propaga (não vira erro tipado).
                LocalProgress.set(LocalPhase.Idle)
                throw e
            }
            // Mid-stream: repetir duplicaria parciais já emitidas — falha honesta.
            if (streamStarted) {
                LocalProgress.set(LocalPhase.Idle)
                throw lastError ?: ProviderError(
                    ProviderErrorCode.NETWORK, "Sem conexão com o provedor.", id, attempts
                )
            }
            if (attempts < tries) delay(RETRY_BACKOFF_MS * attempts)
        }
        LocalProgress.set(LocalPhase.Idle)
        throw lastError ?: ProviderError(
            ProviderErrorCode.UNAVAILABLE, "Modelo remoto indisponível.", id, attempts
        )
    }

    companion object {
        /** Modelo padrão (custo-benefício); trocável para [MODEL_PRO] na UI. */
        const val MODEL_FLASH = "deepseek-flash"
        const val MODEL_PRO = "deepseek-v4-pro"
        const val ENDPOINT = "https://api.deepseek.com/v1"

        /** Thinking desligado por padrão (fidelidade com pouco token). */
        const val REASONING_EFFORT = "low"

        /**
         * Corpo chat/completions (puro/testável). `stream:true` + usage no fim;
         * structured output estrito só no caminho de proposta (mesmo schema do
         * Qwen/Groq — parse local idêntico).
         */
        fun requestBody(
            model: String = MODEL_FLASH,
            system: String,
            user: String,
            maxOutputTokens: Int = DEFAULT_LLM_MAX_OUTPUT_TOKENS,
            editProposal: Boolean = false,
            jsonSchema: String? = null,
            reasoningEffort: String = REASONING_EFFORT,
            stream: Boolean = true,
        ): String {
            val sb = StringBuilder("{\"model\":")
            sb.append(GeminiProvider.jsonEscape(model))
            sb.append(",\"messages\":[{\"role\":\"system\",\"content\":")
            sb.append(GeminiProvider.jsonEscape(system))
            sb.append("},{\"role\":\"user\",\"content\":")
            sb.append(GeminiProvider.jsonEscape(user))
            sb.append("}],\"temperature\":0.2,\"max_tokens\":")
            sb.append(maxOutputTokens)
            sb.append(",\"reasoning_effort\":")
            sb.append(GeminiProvider.jsonEscape(reasoningEffort))
            if (stream) {
                sb.append(",\"stream\":true,\"stream_options\":{\"include_usage\":true}")
            }
            if (editProposal) {
                sb.append(",\"response_format\":{\"type\":\"json_schema\",\"json_schema\":")
                sb.append(QwenProvider.EDIT_PROPOSAL_SCHEMA)
                sb.append("}")
            } else if (jsonSchema != null) {
                sb.append(",\"response_format\":{\"type\":\"json_schema\",\"json_schema\":")
                sb.append(jsonSchema)
                sb.append("}")
            }
            sb.append("}")
            return sb.toString()
        }

        /**
         * Interpreta uma linha SSE. `data:` com delta de conteúdo vira
         * [SseEvent.Delta]; `[DONE]` vira [SseEvent.Done]; o resto (role,
         * usage, finish_reason, linhas vazias) vira `null`. Puro/testável.
         */
        fun parseSseLine(line: String): SseEvent? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return null
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") return SseEvent.Done
            if (!payload.contains("\"content\"")) return null
            val m = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(payload)
                ?: return null
            val text = GeminiProvider.jsonUnescape(m.groupValues[1])
            return if (text.isEmpty()) null else SseEvent.Delta(text)
        }

        /** Tokens do bloco `usage` (último chunk do stream); null quando ausente. */
        fun parseUsage(body: String): LlmUsage? {
            fun num(key: String): Int? {
                val m = Regex("\"$key\"\\s*:\\s*(\\d+)").find(body) ?: return null
                return m.groupValues[1].toIntOrNull()
            }
            val input = num("prompt_tokens") ?: return null
            val output = num("completion_tokens") ?: 0
            val total = num("total_tokens") ?: (input + output)
            return LlmUsage(input, output, total)
        }

        /** Motivo de término do último chunk; null quando ausente. Puro/testável. */
        fun parseFinishReason(line: String): String? {
            val m = Regex("\"finish_reason\"\\s*:\\s*\"([^\"]+)\"").find(line)
                ?: return null
            return m.groupValues[1].ifBlank { null }
        }
    }
}

/** Evento interpretado de uma linha SSE. */
sealed interface SseEvent {
    data class Delta(val text: String) : SseEvent
    data object Done : SseEvent
}

/**
 * Transporte de streaming injetável: a implementação real usa
 * HttpURLConnection; os testes usam fake (sem rede na JVM). Em 200, cada
 * linha da resposta é entregue a [onLine]; o body só é preenchido em erro.
 */
interface DeepSeekStreamClient {
    @Throws(Exception::class)
    suspend fun postStream(
        url: String,
        body: String,
        timeoutMs: Long,
        headers: Map<String, String>,
        onLine: (String) -> Unit,
    ): LlmHttpClient.HttpResult
}

/** Transporte real via HttpURLConnection (SSE linha a linha). */
class UrlConnectionDeepSeekStreamClient : DeepSeekStreamClient {
    private val lock = kotlinx.coroutines.sync.Mutex()

    override suspend fun postStream(
        url: String,
        body: String,
        timeoutMs: Long,
        headers: Map<String, String>,
        onLine: (String) -> Unit,
    ): LlmHttpClient.HttpResult = withContext(Dispatchers.IO) {
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
                if (status == 200) {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            onLine(line)
                            line = reader.readLine()
                        }
                    }
                    LlmHttpClient.HttpResult(200, "", UrlConnectionHttpClient.rateLimitHeaders(conn.headerFields))
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    LlmHttpClient.HttpResult(status, err, UrlConnectionHttpClient.rateLimitHeaders(conn.headerFields))
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}

/**
 * Seleção de prompt com paridade ao GeminiProvider/QwenProvider (mesmo
 * LlmRequest => mesmo conteúdo; só o transporte muda). T2 refina o caminho de
 * chat para separar prefixo estável (cache) do volátil.
 */
private fun DeepSeekProvider.deepSeekPromptsFor(request: LlmRequest): Pair<String, String> {
    val base: Pair<String, String> = if (request.oratorySpec != null) {
        val user = com.bettertalker.app.data.copilot.OratoryGeneration.buildPrompt(
            request.oratorySpec,
            request.message.ifBlank { request.brief }
        )
        "" to user
    } else if (request.responseFormat == ResponseFormat.EDIT_PROPOSAL && request.editMode != null) {
        val user = com.bettertalker.app.data.copilot.buildEditProposalPrompt(
            mode = request.editMode,
            text = request.text,
            pack = request.contextPack
                ?: com.bettertalker.app.data.domain.ContextPack(emptyList(), emptyList()),
            blockTitle = request.blockTitle,
            blockMinutes = request.blockMinutes,
            brief = request.brief,
            legacyPassages = request.contextPassages
        )
        "" to user
    } else {
        val user = com.bettertalker.app.data.copilot.buildChatPrompt(
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
        "" to user
    }
    val block = request.contextBlock?.takeIf { it.isNotBlank() } ?: return base
    return base.first to ("## CONTEXTO DO DOSSIÊ (seção em foco no editor)\n$block\n\n" + base.second)
}
