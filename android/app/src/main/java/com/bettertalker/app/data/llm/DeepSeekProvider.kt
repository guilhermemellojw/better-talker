package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.planning.looksTruncated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * o contrato do [LlmProvider]. `reasoning_content` é separado em
 * [LocalProgress.reasoningText] e NUNCA entra no texto da resposta. O gate de
 * alucinação do chat roda depois, inalterado.
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
        // T1 (modo colar e conversar): texto puro sempre — nunca schema.
        val wantProposal = request.minimalPaste == null &&
            request.responseFormat == ResponseFormat.EDIT_PROPOSAL
        // T1 (P0): resposta estruturada (proposta/oratória) usa cascata
        // json_schema → json_object → texto puro — o DeepSeek pode rejeitar
        // `response_format` json_schema (400 "unavailable now").
        val structured = wantProposal || request.responseFormat == ResponseFormat.JSON_SCHEMA
        val modes = if (structured) {
            listOf(
                DeepSeekStructuredMode.SCHEMA,
                DeepSeekStructuredMode.JSON_OBJECT,
                DeepSeekStructuredMode.TEXT,
            )
        } else {
            listOf(DeepSeekStructuredMode.TEXT)
        }
        // Memória de sessão: schema já rejeitado antes → começa no fallback.
        var modeIndex = if (structured && jsonSchemaRejected) 1 else 0
        // T1 (P0): resposta estruturada não usa thinking — o reasoning consome
        // `max_tokens` e devolve content vazio (finish=length) em prompts
        // longos (oratória/proposta). T4 (Fase 2.1): o modo colar também usa
        // "none" — o esboço íntegro + histórico estouravam o orçamento no
        // thinking (validado no A34: maioria dos turnos exigia retry).
        val effort = reasoningEffortFor(
            structured = structured,
            minimalPaste = request.minimalPaste != null,
            configured = reasoningEffort,
        )
        // T2 — modelo de raciocínio pode consumir todo o orçamento no thinking
        // e devolver content vazio (finish_reason=length). Nesse caso o
        // provider refaz UMA vez com orçamento maior antes de falhar.
        // #8f: o modo "Profundo" (reasoning "high") consome o orçamento no
        // thinking — sobe o teto default para evitar o retry por content vazio.
        var budget = outputBudgetFor(effort, request.maxOutputTokens)
        var budgetBumped = false
        val url = "${endpoint.trimEnd('/')}/chat/completions"
        // Autorização e negociação do stream via header (nunca na URL, nunca em log).
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "text/event-stream",
        )
        var attempts = 0
        var lastError: Exception? = null
        // Aviso da PRÓXIMA tentativa (definido no fim da anterior). A UI mostra
        // "Tentando novamente…" em vez de assistir ao parcial sumir em silêncio.
        var retryNotice: String? = null
        val tries = request.maxAttempts.coerceIn(1, 4)
        while (attempts < tries) {
            attempts++
            val sb = StringBuilder()
            var streamStarted = false
            var sawReasoning = false
            var usageJson: String? = null
            var finish: String? = null
            LocalProgress.beginAttempt(attempts, retryNotice)
            retryNotice = null
            val mode = modes[modeIndex]
            // T1 (Bug #15): a instrução JSON vale SÓ para requisições
            // estruturadas — o enum TEXT é compartilhado com o 3º passo do
            // fallback e vazava "Responda APENAS com JSON" para o chat comum.
            val system = if (structured) {
                prompts.first.withStructuredInstruction(mode)
            } else {
                prompts.first
            }
            val body = requestBody(
                model = model,
                system = system,
                user = prompts.second,
                maxOutputTokens = budget,
                editProposal = wantProposal,
                jsonSchema = if (request.responseFormat == ResponseFormat.JSON_SCHEMA) request.jsonSchema else null,
                reasoningEffort = effort,
                structured = mode,
            )
            try {
                val res = withContext(Dispatchers.IO) {
                    stream.postStream(
                        url, body,
                        request.timeoutMs.takeIf { it > 0 } ?: timeoutMs,
                        headers
                    ) { line ->
                        // #reasoning: uma linha pode trazer reasoning_content
                        // e content juntos; processa os dois eventos.
                        val events = parseSseEvents(line)
                        // Metadados (usage/finish_reason) podem vir na mesma
                        // linha de um delta ou em chunk próprio.
                        if (line.contains("\"usage\"")) {
                            usageJson = line.removePrefix("data:").trim()
                        }
                        Regex("\"finish_reason\"\\s*:\\s*\"([^\"]+)\"")
                            .find(line)?.let { finish = it.groupValues[1].ifBlank { null } }
                        events.forEach { ev ->
                            when (ev) {
                                is SseEvent.ReasoningDelta -> {
                                    sawReasoning = true
                                    LocalProgress.appendReasoning(ev.text)
                                }
                                is SseEvent.Delta -> {
                                    if (!streamStarted) {
                                        streamStarted = true
                                        LocalProgress.set(LocalPhase.Generating)
                                    }
                                    sb.append(ev.text)
                                    LocalProgress.appendPartial(ev.text)
                                }
                                SseEvent.Done -> Unit
                            }
                        }
                    }
                }
                when {
                    res.status == 200 -> {
                        val text = sb.toString()
                        val vazio = text.isBlank()
                        // #11-fix: fragmento CORTADO não-vazio também merece 1
                        // retry com budget maior — antes passava cru como resposta.
                        val vazioPorLength = vazio && finish == "length"
                        val cortado = !vazio && finish == "length" &&
                            looksTruncated(text, finish)
                        if (shouldRetryOnLength(vazioPorLength, cortado, budgetBumped)) {
                            budgetBumped = true
                            budget = (budget * 2).coerceAtMost(4096)
                            log(
                                "deepseek ${if (vazio) "vazio" else "cortado"} por length; " +
                                    "retry com budget=$budget tentativa=$attempts"
                            )
                            lastError = ProviderError(
                                ProviderErrorCode.INVALID_RESPONSE,
                                "Orçamento de tokens esgotado antes da resposta.", id, attempts
                            )
                            retryNotice = "Tentando novamente com mais espaço…"
                            continue
                        }
                        if (vazio) {
                            val msg = if (finish == "length") {
                                "Orçamento de tokens esgotado antes da resposta."
                            } else {
                                "Resposta vazia do DeepSeek."
                            }
                            throw ProviderError(ProviderErrorCode.INVALID_RESPONSE, msg, id, attempts)
                        }
                        // #11-fix (c): torna visível o corte que antes passava mudo.
                        if (finish == "length") {
                            android.util.Log.w(
                                "DeepSeek",
                                "resposta cortada por length: ${text.length} chars"
                            )
                        }
                        // #reasoning: diagnóstico honesto (sem conteúdo, sem
                        // chave) para validar no device se o modelo emite.
                        if (sawReasoning) {
                            log(
                                "deepseek reasoning_content presente " +
                                    "(${LocalProgress.reasoningText.value.length} chars); " +
                                    "descartado da resposta"
                            )
                        }
                        LocalProgress.set(LocalPhase.Done)
                        // T1: no modo texto puro o modelo pode vir com prosa em
                        // volta do JSON; extrai o primeiro objeto balanceado.
                        val finalText = if (structured && mode == DeepSeekStructuredMode.TEXT) {
                            extractFirstJsonObject(text) ?: text
                        } else {
                            text
                        }
                        return LlmResponse(
                            finalText,
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
                        // T1 (P0): só a rejeição ESPECÍFICA de response_format
                        // cai no fallback; 400 genérico propaga normalmente.
                        if (structured && modeIndex < modes.lastIndex &&
                            isResponseFormatUnavailable(res.body)
                        ) {
                            if (mode == DeepSeekStructuredMode.SCHEMA) jsonSchemaRejected = true
                            modeIndex++
                            log(
                                "deepseek 400 response_format indisponível; " +
                                    "fallback=${modes[modeIndex].name}"
                            )
                            attempts-- // fallback não consome retry transitório
                            continue
                        }
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
            if (attempts < tries) {
                retryNotice = "Tentando novamente…"
                delay(RETRY_BACKOFF_MS * attempts)
            }
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
         * #17-fix: modo "Profundo" do chip — mais raciocínio nas rotas de
         * chat (só onde não é estruturado; ver [reasoningEffortFor]).
         */
        const val REASONING_EFFORT_DEEP = "high"

        /**
         * #8f: orçamento do turno no modo "Profundo" — o reasoning consome o
         * teto default (1000) e o content volta vazio (retry + delay no A34).
         */
        const val DEEP_OUTPUT_TOKENS = 2000

        /**
         * #8f: teto efetivo de saída — sobe para [DEEP_OUTPUT_TOKENS] só no
         * modo Profundo e só quando o pedido não trouxe um teto maior.
         * Puro/testável.
         */
        fun outputBudgetFor(effort: String, requested: Int): Int =
            if (effort == REASONING_EFFORT_DEEP && requested <= DEFAULT_LLM_MAX_OUTPUT_TOKENS) {
                DEEP_OUTPUT_TOKENS
            } else {
                requested
            }

        /**
         * T1 (P0) — resposta estruturada (oratória/proposta): thinking desligado.
         * O reasoning consome `max_tokens` antes do content e devolve resposta
         * vazia (finish=length) em prompts longos; "none" devolve o JSON direto.
         */
        const val STRUCTURED_REASONING_EFFORT = "none"

        /**
         * T4 (Fase 2.1) — modo colar e conversar: thinking desligado pelo mesmo
         * motivo (esboço íntegro + histórico estouravam o orçamento antes do
         * content; a maioria dos turnos exigia retry no A34).
         */
        const val MINIMAL_REASONING_EFFORT = "none"

        /**
         * T4 (Fase 2.1) — esforço de reasoning por rota. Puro/testável.
         */
        fun reasoningEffortFor(structured: Boolean, minimalPaste: Boolean, configured: String): String =
            when {
                structured -> STRUCTURED_REASONING_EFFORT
                minimalPaste -> MINIMAL_REASONING_EFFORT
                else -> configured
            }

        /**
         * Corpo chat/completions (puro/testável). `stream:true` + usage no fim;
         * structured output estrito só no caminho de proposta (mesmo schema do
         * parse local).
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
            /** T1 (P0): modo do `response_format` na cascata de fallback. */
            structured: DeepSeekStructuredMode = DeepSeekStructuredMode.SCHEMA,
        ): String {
            val sb = StringBuilder("{\"model\":")
            sb.append(JsonCodec.escape(model))
            sb.append(",\"messages\":[{\"role\":\"system\",\"content\":")
            sb.append(JsonCodec.escape(system))
            sb.append("},{\"role\":\"user\",\"content\":")
            sb.append(JsonCodec.escape(user))
            sb.append("}],\"temperature\":0.2,\"max_tokens\":")
            sb.append(maxOutputTokens)
            sb.append(",\"reasoning_effort\":")
            sb.append(JsonCodec.escape(reasoningEffort))
            if (stream) {
                sb.append(",\"stream\":true,\"stream_options\":{\"include_usage\":true}")
            }
            when (structured) {
                DeepSeekStructuredMode.SCHEMA -> {
                    if (editProposal) {
                        sb.append(",\"response_format\":{\"type\":\"json_schema\",\"json_schema\":")
                        sb.append(LlmSchemas.EDIT_PROPOSAL_SCHEMA)
                        sb.append("}")
                    } else if (jsonSchema != null) {
                        sb.append(",\"response_format\":{\"type\":\"json_schema\",\"json_schema\":")
                        sb.append(jsonSchema)
                        sb.append("}")
                    }
                }
                DeepSeekStructuredMode.JSON_OBJECT -> {
                    sb.append(",\"response_format\":{\"type\":\"json_object\"}")
                }
                DeepSeekStructuredMode.TEXT -> Unit
            }
            sb.append("}")
            return sb.toString()
        }

        /**
         * Interpreta TODOS os eventos de uma linha SSE. `reasoning_content`
         * vira [SseEvent.ReasoningDelta] e `content` vira [SseEvent.Delta];
         * quando os dois vêm no mesmo delta, a ordem é raciocínio → conteúdo.
         * `[DONE]` vira [SseEvent.Done]; o resto (role, usage, finish_reason,
         * linhas vazias) vira lista vazia. Puro/testável.
         */
        fun parseSseEvents(line: String): List<SseEvent> {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return emptyList()
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") return listOf(SseEvent.Done)
            return buildList {
                sseStringField(payload, "reasoning_content")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(SseEvent.ReasoningDelta(it)) }
                sseStringField(payload, "content")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(SseEvent.Delta(it)) }
            }
        }

        /**
         * Compatibilidade: devolve UM evento por linha, com prioridade para
         * [SseEvent.Delta] quando a linha traz raciocínio e conteúdo juntos.
         * O provider usa [parseSseEvents] para não perder o raciocínio.
         * Puro/testável.
         */
        fun parseSseLine(line: String): SseEvent? {
            val events = parseSseEvents(line)
            return events.firstOrNull { it is SseEvent.Delta } ?: events.firstOrNull()
        }

        /** Extrai um campo string JSON do payload SSE (null quando ausente/nulo). */
        private fun sseStringField(payload: String, field: String): String? {
            val m = Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(payload)
                ?: return null
            return JsonCodec.unescape(m.groupValues[1])
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

        /**
         * T1 (P0) — memória de sessão: o endpoint já rejeitou `json_schema`?
         * Depois da primeira rejeição a cascata começa em `json_object` (evita
         * repetir a tentativa que sabidamente falha). In-memory de propósito:
         * vale para o processo; reinicia ao abrir o app.
         */
        @Volatile
        var jsonSchemaRejected: Boolean = false

        /**
         * T1 (P0) — rejeição ESPECÍFICA de `response_format` (nunca 400
         * genérico). Corpo real observado em 05/10/2026:
         * `This response_format type is unavailable now`.
         */
        fun isResponseFormatUnavailable(body: String): Boolean {
            val b = body.lowercase()
            if (!b.contains("response_format")) return false
            return b.contains("unavailable") || b.contains("not available") ||
                b.contains("unsupported") || b.contains("not supported")
        }

        /**
         * T1 (P0) — primeiro objeto `{...}` balanceado do texto (parse
         * tolerante do modo texto puro, quando o modelo vem com prosa).
         * Puro/testável.
         */
        fun extractFirstJsonObject(text: String): String? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            return null
        }
    }
}

/** T1 (P0): modo de resposta estruturada na cascata de fallback do DeepSeek. */
enum class DeepSeekStructuredMode { SCHEMA, JSON_OBJECT, TEXT }

/**
 * T1 (P0) — instrução por modo: `json_object` exige "JSON" no prompt (contrato
 * OpenAI-compatible); no modo texto puro pede-se JSON sem markdown.
 */
private fun String.withStructuredInstruction(mode: DeepSeekStructuredMode): String {
    val extra = when (mode) {
        DeepSeekStructuredMode.SCHEMA -> null
        DeepSeekStructuredMode.JSON_OBJECT -> "Sua resposta deve ser um objeto JSON válido."
        DeepSeekStructuredMode.TEXT ->
            "Responda APENAS com JSON válido. Não use markdown, não use cercas de código."
    } ?: return this
    return if (isBlank()) extra else "$this\n$extra"
}

/** Evento interpretado de uma linha SSE. */
sealed interface SseEvent {
    /** Delta de `reasoning_content` (raciocínio interno; nunca entra na resposta). */
    data class ReasoningDelta(val text: String) : SseEvent
    /** Delta de `content` (a resposta exibida/persistida). */
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
                            // #4 (UX S): o "Parar" cancela o job; sem esta
                            // checagem o loop bloqueante consome o stream
                            // inteiro antes de observar o cancelamento (o
                            // botão parecia morto por até 1 min).
                            coroutineContext.ensureActive()
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
 * Seleção de prompt com paridade ao contrato antigo (mesmo
 * LlmRequest => mesmo conteúdo; só o transporte muda). T2 refina o caminho de
 * chat para separar prefixo estável (cache) do volátil.
 */
private fun DeepSeekProvider.deepSeekPromptsFor(request: LlmRequest): Pair<String, String> {
    // Fase A: geração com persona própria — system = persona, user = prompt
    // direto (sem chatBriefToText, sem blockText duplicado, sem tail).
    if (request.generationProfile !=
        com.bettertalker.app.domain.planning.GenerationProfile.CHAT
    ) {
        val base = com.bettertalker.app.data.copilot.GenerationPrompts
            .systemPromptFor(request.generationProfile)
            .orEmpty()
        // A persona fala em <texto-do-card> (chat), mas o draft é JSON: nesta
        // chamada o texto vai no campo `text`, sem marcadores de chat.
        val system = if (request.responseFormat == ResponseFormat.JSON_SCHEMA) {
            base + "\n\nFORMATO DESTA CHAMADA: responda APENAS com o JSON solicitado; " +
                "não use <texto-do-card>, cercas de código ou texto fora do JSON."
        } else {
            base
        }
        return system to request.text
    }
    // T1 (modo colar e conversar): prompt minimalista, sem TAIL/regras/RAG.
    request.minimalPaste?.let { pasted ->
        return com.bettertalker.app.data.copilot.buildMinimalPastePrompts(
            pastedText = pasted,
            message = request.message.ifBlank { request.text },
            history = request.history,
        )
    }
    val base: Pair<String, String> = if (request.oratorySpec != null) {
        val user = com.bettertalker.app.data.copilot.OratoryGeneration.buildPrompt(
            request.oratorySpec,
            request.message.ifBlank { request.brief }
        )
        // P0 (alinhamento persona): a geração oratória também fala com a voz
        // do mentor — antes o system ia vazio e só as regras frias guiavam.
        (com.bettertalker.app.data.copilot.CHAT_PERSONA +
            com.bettertalker.app.data.copilot.GENERATION_VOICE_LINE) to user
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
        // T2 — caminho de chat: prefixo estável (system) para o cache persistente;
        // o dossiê entra no system (estável dentro do mesmo tópico).
        val parts = com.bettertalker.app.data.copilot.buildDeepSeekPrompts(
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
            oratory = request.oratory,
            contextBlock = request.contextBlock,
        )
        return parts.system to parts.user
    }
    val block = request.contextBlock?.takeIf { it.isNotBlank() } ?: return base
    return base.first to ("## CONTEXTO DO DOSSIÊ (seção em foco no editor)\n$block\n\n" + base.second)
}

/**
 * #11-fix: decide o retry único por corte de orçamento.
 *
 * - `emptyByLength`: content vazio com finish=length (caso T2 original);
 * - `cut`: fragmento não-vazio cortado por length (caso novo — o stub);
 * - `budgetBumped`: o retry já aconteceu (nunca faz loop).
 *
 * Puro/testável.
 */
internal fun shouldRetryOnLength(
    emptyByLength: Boolean,
    cut: Boolean,
    budgetBumped: Boolean,
): Boolean = (emptyByLength || cut) && !budgetBumped
