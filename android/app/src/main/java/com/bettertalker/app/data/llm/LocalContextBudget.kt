package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.domain.ContextPack

/**
 * F2.4 — política ÚNICA e determinística do orçamento de contexto do Gemma
 * local (LiteRT-LM, ctx [TOTAL_CONTEXT_TOKENS]).
 *
 * Antes desta fase os limites viviam espalhados (LeanRag 2k, histórico da F15,
 * dossiê enxuto) sem uma conta total. Aqui a conta é feita UMA vez, na ordem
 * de prioridade do produto, e o resultado é aplicado ao [LlmRequest] antes de
 * montar o prompt.
 *
 * Orçamento:
 *   [TOTAL_CONTEXT_TOKENS] = janela do motor (espelha `MAX_NUM_TOKENS`).
 *   saída reservada          = `request.maxOutputTokens` (o motor usa o mesmo
 *                              teto para gerar; a janela é compartilhada).
 *   margem de segurança      = [SAFETY_MARGIN_TOKENS] (erro do estimador).
 *   disponível p/ entrada    = TOTAL - saída - margem.
 *
 * Prioridade de alocação (do mais alto para o mais baixo):
 *   1. system (regras de fidelidade)  — fixo, nunca cortado
 *   2. mensagem do usuário            — nunca truncada silenciosamente
 *   3. contexto do tópico (objetivo, abordagem, linha de raciocínio, refs) —
 *      teto [TOPIC_CAP_TOKENS]; corta a CAUDA (refs/overview) preservando o topo
 *   4. evidência RAG                  — reusa [LeanRag] com o teto que couber
 *   5. texto atual do tópico          — teto [TEXT_CAP_TOKENS]
 *   6. histórico                      — teto [HISTORY_CAP_TOKENS], mais antigo
 *      sai primeiro
 *
 * Puro/testável: sem Android, sem IO. A estimativa é de caracteres
 * ([LeanRag.estimateTokens]); o log marca como estimativa, não medição exata.
 */
object LocalContextBudget {

    /** Janela do motor (LitertGemmaEngine.MAX_NUM_TOKENS). */
    const val TOTAL_CONTEXT_TOKENS = 4096

    /** Folga para o erro do estimador chars/4. */
    const val SAFETY_MARGIN_TOKENS = 128

    /** Faixa de saída do motor (`coerceIn(64, 2048)` no LitertGemmaEngine). */
    const val MIN_OUTPUT_TOKENS = 64
    const val MAX_OUTPUT_TOKENS = 2048

    /**
     * Teto do bloco de contexto do tópico (dossiê). Deriva dos tamanhos reais
     * medidos na F2.3 (425–793 tok) com folga; não é a fatia da janela.
     */
    const val TOPIC_CAP_TOKENS = 1100

    /** Teto do texto atual do tópico (P6). */
    const val TEXT_CAP_TOKENS = 800

    /** Teto do histórico (P8). */
    const val HISTORY_CAP_TOKENS = 800

    /**
     * Custo fixo do boilerplate do prompt de chat (foco, rótulos, instruções
     * de resposta, separadores) que não pertence a nenhum segmento. Calibrado
     * por teste (`LocalContextBudgetTest.promptOverheadIsUpperBound`).
     */
    const val PROMPT_OVERHEAD_TOKENS = 260

    /** Marcador de corte visível (mesmo do LeanRag). */
    const val TRUNCATION_MARKER = " […]"

    private const val CHARS_PER_TOKEN = LeanRag.CHARS_PER_TOKEN

    /** Composição final do prompt (tudo em tokens estimados). */
    data class Plan(
        val system: Int,
        val topic: Int,
        /** Recorte da visão global dentro de [topic] (INTRO/CONCLUSÃO). */
        val global: Int,
        val rag: Int,
        val history: Int,
        val blockText: Int,
        val user: Int,
        val overhead: Int,
        val inputTotal: Int,
        val outputReserve: Int,
        val budget: Int,
        /** Segmentos que sofreram corte (nomes estáveis para log). */
        val trimmed: List<String>,
        /**
         * true quando system + overhead + mensagem sozinhos já estouram o
         * orçamento. A mensagem NUNCA é truncada; o caso é registrado.
         */
        val userOverBudget: Boolean = false,
    ) {
        val fits: Boolean get() = inputTotal <= budget
    }

    data class Result(val request: LlmRequest, val plan: Plan)

    fun estimate(text: String): Int = LeanRag.estimateTokens(text)

    /** Tokens orçados de um pack (texto + referência por fonte, como o LeanRag). */
    fun packTokens(pack: ContextPack?): Int =
        pack?.let { p ->
            p.contentSources.sumOf { LeanRag.sourceTokens(it) } +
                p.trainingSources.sumOf { LeanRag.sourceTokens(it) }
        } ?: 0

    /**
     * Aplica a política: devolve o request ajustado (com eventuais cortes) e o
     * plano com a composição final. Nunca lança; nunca trunca a mensagem.
     */
    fun apply(
        request: LlmRequest,
        systemText: String = GEMMA_GROUNDING_BLOCK,
    ): Result {
        val outputReserve = request.maxOutputTokens.coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
        val budget = (TOTAL_CONTEXT_TOKENS - outputReserve - SAFETY_MARGIN_TOKENS).coerceAtLeast(0)

        val system = estimate(systemText)
        val overhead = PROMPT_OVERHEAD_TOKENS
        // A mensagem efetiva é `message`, caindo para `text` (contrato do
        // buildChatPrompt). Nunca é truncada.
        val userText = request.message.ifBlank { request.text }
        val user = estimate(userText)

        var remaining = (budget - system - overhead - user).coerceAtLeast(0)
        val trimmed = mutableListOf<String>()

        // P3 — contexto do tópico (corta a cauda, preserva objetivo/abordagem).
        val topicNeed = estimate(request.contextBlock.orEmpty())
        val topicAlloc = minOf(topicNeed, TOPIC_CAP_TOKENS, remaining)
        remaining -= topicAlloc
        val contextBlock = if (topicAlloc >= topicNeed) {
            request.contextBlock
        } else {
            trimmed += "topic"
            truncateToTokens(request.contextBlock.orEmpty(), topicAlloc)
        }

        // P4 — evidência RAG (reusa o LeanRag com o teto que couber).
        val ragNeed = packTokens(request.contextPack) + request.contextPassages.sumOf { estimate(it) }
        val ragAlloc = minOf(ragNeed, remaining)
        remaining -= ragAlloc
        val ragTrimmed = trimRag(request, ragAlloc)
        if (ragAlloc < ragNeed) trimmed += "rag"

        // P5 — texto atual do tópico.
        val textNeed = estimate(request.text)
        val textAlloc = minOf(textNeed, TEXT_CAP_TOKENS, remaining)
        remaining -= textAlloc
        val text = if (textAlloc >= textNeed) {
            request.text
        } else {
            trimmed += "text"
            truncateToTokens(request.text, textAlloc)
        }

        // P6 — histórico (mais antigo sai primeiro).
        val historyNeed = historyTokens(request.history)
        val historyAlloc = minOf(historyNeed, HISTORY_CAP_TOKENS, remaining)
        remaining -= historyAlloc
        val history = if (historyAlloc >= historyNeed) {
            request.history
        } else {
            trimmed += "history"
            trimHistory(request.history, historyAlloc)
        }

        val inputTotal = system + overhead + user + topicAlloc + ragAlloc + textAlloc + historyAlloc
        val plan = Plan(
            system = system,
            topic = topicAlloc,
            global = overviewTokens(contextBlock.orEmpty()),
            rag = ragAlloc,
            history = historyAlloc,
            blockText = textAlloc,
            user = user,
            overhead = overhead,
            inputTotal = inputTotal,
            outputReserve = outputReserve,
            budget = budget,
            trimmed = trimmed,
            userOverBudget = (system + overhead + user) > budget,
        )
        return Result(
            request = request.copy(
                text = text,
                contextBlock = contextBlock,
                contextPack = ragTrimmed.pack,
                contextPassages = ragTrimmed.passages,
                history = history,
            ),
            plan = plan,
        )
    }

    private data class RagTrim(val pack: ContextPack?, val passages: List<String>)

    /** Divide o teto do RAG entre o pack estruturado e os trechos legados. */
    private fun trimRag(request: LlmRequest, alloc: Int): RagTrim {
        if (alloc <= 0) {
            return if (packTokens(request.contextPack) == 0 && request.contextPassages.isEmpty()) {
                RagTrim(request.contextPack, request.contextPassages)
            } else {
                RagTrim(ContextPack(emptyList(), emptyList()), emptyList())
            }
        }
        val packAlloc = minOf(packTokens(request.contextPack), alloc)
        val pack = LeanRag.compact(request.contextPack, packAlloc)
        val remaining = alloc - packTokens(pack)
        val passages = takePassagesFitting(request.contextPassages, remaining)
        return RagTrim(pack, passages)
    }

    private fun takePassagesFitting(passages: List<String>, alloc: Int): List<String> {
        if (alloc <= 0) return emptyList()
        val out = mutableListOf<String>()
        var remaining = alloc
        for (p in passages) {
            if (p.isBlank()) continue
            val cost = estimate(p)
            if (cost <= remaining) {
                out += p
                remaining -= cost
            } else {
                break
            }
        }
        return out
    }

    fun historyTokens(history: List<ChatTurn>): Int =
        history.sumOf { estimate(it.text) + HISTORY_TURN_OVERHEAD_TOKENS }

    /** Mantém as trocas mais recentes que couberem; descarta as mais antigas. */
    private fun trimHistory(history: List<ChatTurn>, alloc: Int): List<ChatTurn> {
        if (alloc <= 0) return emptyList()
        val out = ArrayDeque<ChatTurn>()
        var remaining = alloc
        for (turn in history.asReversed()) {
            val cost = estimate(turn.text) + HISTORY_TURN_OVERHEAD_TOKENS
            if (cost <= remaining) {
                out.addFirst(turn)
                remaining -= cost
            } else {
                break
            }
        }
        return out.toList()
    }

    /** Trunca por tokens no último limite de palavra; acrescenta o marcador. */
    fun truncateToTokens(text: String, tokens: Int): String {
        if (tokens <= 0) return ""
        val charBudget = tokens * CHARS_PER_TOKEN
        if (text.length <= charBudget) return text
        val cut = text.take(charBudget)
        val boundary = cut.indexOfLast { it == '\n' || it == ' ' }
        val trimmed = if (boundary > charBudget / 2) cut.substring(0, boundary) else cut
        return trimmed.trimEnd() + TRUNCATION_MARKER
    }

    /**
     * Tokens da visão global (bloco `## ESTRUTURA DO DISCURSO`) dentro do
     * contexto do tópico. 0 quando não há (BODY). Ajuda o log a separar
     * "global" de "topic" sem reprocessar o dossiê.
     */
    fun overviewTokens(contextBlock: String): Int {
        val marker = "## ESTRUTURA DO DISCURSO"
        val start = contextBlock.indexOf(marker)
        if (start < 0) return 0
        val rest = contextBlock.substring(start + marker.length)
        val end = rest.indexOf("\n## ")
        val block = if (end >= 0) rest.substring(0, end) else rest
        return estimate(block)
    }

    private const val HISTORY_TURN_OVERHEAD_TOKENS = 6
}
