package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.buildEditProposalPrompt
import kotlinx.coroutines.delay

/**
 * Fase 20-F1 — transporte Groq/Qwen no Android.
 *
 * Espelho do `QwenProvider` do web (F20-E): mesmo endpoint OpenAI-compatible,
 * mesmo modelo `qwen/qwen3.8-27b`, `reasoning_effort=low`, structured output
 * `json_schema` estrito no caminho de proposta, mesma seleção de prompt do
 * GeminiProvider (oratorySpec → especializado; edit-proposal → proposta;
 * senão chat com structural+oratory). O transporte não altera a inteligência.
 *
 * Sem chave => erro honesto UNAVAILABLE (nunca texto fake, nunca fallback
 * silencioso). Retry SÓ em transitório (429/5xx/rede), como o GeminiProvider.
 * A chave nunca aparece em logs, erros ou exceções.
 */
class QwenProvider(
    private val apiKey: String,
    override val model: String = GROQ_MODEL,
    private val endpoint: String = GROQ_ENDPOINT,
    private val reasoningEffort: String = REASONING_EFFORT,
    private val timeoutMs: Long = DEFAULT_LLM_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_LLM_MAX_ATTEMPTS,
    private val http: LlmHttpClient = UrlConnectionHttpClient(),
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) }
) : LlmProvider {

    override val id: String = "qwen"

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val started = System.currentTimeMillis()
        if (apiKey.isBlank()) {
            throw ProviderError(ProviderErrorCode.UNAVAILABLE,
                "Configure a chave do Groq na tela Modelo IA para usar o Qwen.", id, 0)
        }
        // Paridade de contrato com o GeminiProvider: o mesmo LlmRequest gera
        // o mesmo prompt; só o transporte muda.
        val prompts = promptsFor(request)
        val wantProposal = request.responseFormat == ResponseFormat.EDIT_PROPOSAL
        val body = requestBody(
            system = prompts.first,
            user = prompts.second,
            maxOutputTokens = request.maxOutputTokens,
            editProposal = wantProposal,
            jsonSchema = if (request.responseFormat == ResponseFormat.JSON_SCHEMA) request.jsonSchema else null,
        )
        val url = "${endpoint.trimEnd('/')}/chat/completions"
        // Authorization via header (nunca na URL, nunca em log).
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        var attempts = 0
        var lastError: Exception? = null
        val tries = request.maxAttempts.coerceIn(1, 4)
        while (attempts < tries) {
            attempts++
            try {
                val res = http.postJson(url, body,
                    request.timeoutMs.takeIf { it > 0 } ?: timeoutMs, headers)
                when {
                    res.status == 200 -> {
                        val text = parseChatContent(res.body)
                            ?: throw ProviderError(ProviderErrorCode.INVALID_RESPONSE,
                                "Resposta do Qwen em formato inesperado.", id, attempts)
                        return LlmResponse(text, LlmResponseMeta(id, model,
                            System.currentTimeMillis() - started, attempts, offline = false,
                            usage = parseUsage(res.body), rateLimit = res.headers,
                            finishReason = parseFinishReason(res.body)))
                    }
                    res.status == 429 -> {
                        log("qwen 429 attempt=$attempts")
                        lastError = ProviderError(ProviderErrorCode.RATE_LIMIT,
                            "Limite de requisições atingido.", id, attempts)
                    }
                    res.status >= 500 -> {
                        log("qwen ${res.status} attempt=$attempts")
                        lastError = ProviderError(ProviderErrorCode.UNAVAILABLE,
                            "Modelo remoto indisponível.", id, attempts)
                    }
                    res.status == 401 || res.status == 403 -> throw ProviderError(
                        ProviderErrorCode.AUTHENTICATION, "Chave de API inválida.", id, attempts)
                    else -> {
                        log("qwen ${res.status} attempt=$attempts body=${res.body.take(200)}")
                        throw ProviderError(
                            ProviderErrorCode.INVALID_REQUEST, "Requisição rejeitada.", id, attempts)
                    }
                }
            } catch (e: ProviderError) {
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
        /** Modelo exato validado no web (§4): sem fallback silencioso. */
        const val GROQ_MODEL = "qwen/qwen3.8-27b"
        const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1"
        /** Mesmo valor validado no web (§9): fidelidade com pouco token. */
        const val REASONING_EFFORT = "low"

        /**
         * Corpo chat/completions. Puro/testável. Structured output estrito
         * SÓ no caminho de proposta (mesma regra do web).
         */
        fun requestBody(
            system: String,
            user: String,
            maxOutputTokens: Int = DEFAULT_LLM_MAX_OUTPUT_TOKENS,
            editProposal: Boolean = false,
            jsonSchema: String? = null,
        ): String {
            val sb = StringBuilder("{\"model\":")
            sb.append(GeminiProvider.jsonEscape(GROQ_MODEL))
            sb.append(",\"messages\":[{\"role\":\"system\",\"content\":")
            sb.append(GeminiProvider.jsonEscape(system))
            sb.append("},{\"role\":\"user\",\"content\":")
            sb.append(GeminiProvider.jsonEscape(user))
            sb.append("}],\"temperature\":0.2,\"max_tokens\":")
            sb.append(maxOutputTokens)
            sb.append(",\"reasoning_effort\":")
            sb.append(GeminiProvider.jsonEscape(REASONING_EFFORT))
            if (editProposal) {
                sb.append(",\"response_format\":{\"type\":\"json_schema\",\"json_schema\":")
                sb.append(EDIT_PROPOSAL_SCHEMA)
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
         * Schema estrito da proposta (§8): espelho do EDIT_PROPOSAL_JSON_SCHEMA
         * do web — insert/replace + conteúdo, que é o que parseEditProposal aceita.
         */
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

        /**
         * Extrai choices[0].message.content; null se ausente/vazio.
         * Puro/testável.
         */
        fun parseChatContent(body: String): String? {
            val m = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?: return null
            return GeminiProvider.jsonUnescape(m.groupValues[1]).ifBlank { null }
        }

        /** Tokens do `usage` do Groq; null quando ausentes. Puro/testável. */
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

        /**
         * Motivo de término (`choices[0].finish_reason`: "stop"/"length"/…).
         * Null quando ausente. Puro/testável.
         */
        fun parseFinishReason(body: String): String? {
            val m = Regex("\"finish_reason\"\\s*:\\s*\"([^\"]+)\"").find(body)
                ?: return null
            return m.groupValues[1].ifBlank { null }
        }

        /** Transitório = vale retry. Definitivo = falha imediata. Puro/testável. */
        fun isTransientStatus(status: Int): Boolean =
            status == 429 || status >= 500
    }
}

/**
 * Seleção de prompt com paridade total ao GeminiProvider: o mesmo
 * LlmRequest produz system+user equivalentes; só o transporte muda.
 */
private fun QwenProvider.promptsFor(request: LlmRequest): Pair<String, String> {
    // O system do web é o SYSTEM_PROMPT do Copilot; no Android o prompt de
    // chat já embute as instruções (buildChatPrompt). Para o oratório, o
    // buildPrompt do modo já contém as regras — system vazio evita duplicar.
    if (request.oratorySpec != null) {
        val user = com.bettertalker.app.data.copilot.OratoryGeneration.buildPrompt(
            request.oratorySpec,
            request.message.ifBlank { request.brief }
        )
        return "" to user
    }
    if (request.responseFormat == ResponseFormat.EDIT_PROPOSAL && request.editMode != null) {
        val user = buildEditProposalPrompt(
            mode = request.editMode,
            text = request.text,
            pack = request.contextPack
                ?: com.bettertalker.app.data.domain.ContextPack(emptyList(), emptyList()),
            blockTitle = request.blockTitle,
            blockMinutes = request.blockMinutes,
            brief = request.brief,
            legacyPassages = request.contextPassages
        )
        return "" to user
    }
    val user = buildChatPrompt(
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
    return "" to user
}
