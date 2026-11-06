package com.bettertalker.app.data.planning

/**
 * Guarda contra o JSON interno do gerador de estrutura vazando para o chat
 * como texto comum (especialmente em turno cancelado/streaming interrompido).
 *
 * O JSON do outline tem `title`, `summary`/`sections` e as chaves de seção
 * (`mainIdea`/`mainldea`). Um texto de conversa normal não começa com `{`.
 * Puro/testável.
 */
fun looksLikeOutlineJson(text: String): Boolean {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return false
    val lower = trimmed.lowercase()
    val hasSections = lower.contains("\"sections\"") || lower.contains("\"section\"")
    val hasTitle = lower.contains("\"title\"")
    val hasSummary = lower.contains("\"summary\"") || lower.contains("\"resumo\"")
    val hasMainIdea = lower.contains("\"mainidea\"") ||
        lower.contains("\"mainldea\"") ||
        lower.contains("\"main idea\"") ||
        lower.contains("\"main_idea\"")
    return hasSections && hasTitle && (hasSummary || hasMainIdea)
}

/** Texto amigável que substitui o JSON interno no chat. */
const val OUTLINE_JSON_CHAT_FALLBACK =
    "A estrutura foi interrompida antes de virar card. Tente novamente."
