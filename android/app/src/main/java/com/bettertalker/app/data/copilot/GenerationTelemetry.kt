package com.bettertalker.app.data.copilot

/**
 * Telemetria única de geração (chat, oratória e draft).
 *
 * Puro/testável: só formata; o chamador decide onde logar. Campos alinhados
 * ao bloco de geração longa: rota, orçamento, finish_reason, tamanho,
 * truncamento, card e thinking.
 */
data class GenerationTelemetry(
    val route: String,
    val budget: Int,
    val finishReason: String?,
    val chars: Int,
    val words: Int,
    val truncated: Boolean,
    val hasCard: Boolean,
    val thinking: Boolean,
    /** Uso real do provider (null quando o endpoint não informa). */
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedTokens: Int? = null,
    /** Tamanho do histórico/contexto enviado (para explicar o custo). */
    val historyTurns: Int = 0,
    val historyChars: Int = 0,
    val refsCount: Int = 0,
    val inventoryChars: Int = 0,
) {
    fun line(): String = buildString {
        append("generation|route=").append(route)
        append("|budget=").append(budget)
        append("|finish_reason=").append(finishReason ?: "?")
        append("|chars=").append(chars)
        append("|words=").append(words)
        append("|truncated=").append(truncated)
        append("|hasCard=").append(hasCard)
        append("|thinking=").append(thinking)
        append("|in_tokens=").append(inputTokens ?: "?")
        append("|out_tokens=").append(outputTokens ?: "?")
        append("|cached_tokens=").append(cachedTokens ?: "?")
        append("|history_turns=").append(historyTurns)
        append("|history_chars=").append(historyChars)
        append("|refs=").append(refsCount)
        append("|inventory_chars=").append(inventoryChars)
    }
}

/** Conta palavras de um texto (HTML/markdown contam como tokens de fala). */
fun speechWordCount(text: String): Int =
    text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

/** Monta a telemetria de uma resposta gerada. */
fun generationTelemetry(
    route: String,
    text: String,
    budget: Int = 0,
    finishReason: String? = null,
    truncated: Boolean = false,
    thinking: Boolean = false,
    inputTokens: Int? = null,
    outputTokens: Int? = null,
    cachedTokens: Int? = null,
    historyTurns: Int = 0,
    historyChars: Int = 0,
    refsCount: Int = 0,
    inventoryChars: Int = 0,
): GenerationTelemetry = GenerationTelemetry(
    route = route,
    budget = budget,
    finishReason = finishReason,
    chars = text.length,
    words = speechWordCount(text),
    truncated = truncated,
    hasCard = text.contains("<texto-do-card>"),
    thinking = thinking,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedTokens = cachedTokens,
    historyTurns = historyTurns,
    historyChars = historyChars,
    refsCount = refsCount,
    inventoryChars = inventoryChars,
)
