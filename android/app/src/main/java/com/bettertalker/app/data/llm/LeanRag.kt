package com.bettertalker.app.data.llm

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource

/**
 * F2.1 — RAG enxuto para o Gemma local.
 *
 * O prefill é o custo dominante do fluxo local (2k tokens ≈ 8,4 s no A34 via
 * GPU; ~61 s no Qwen/CPU). Esta camada limita o contexto RAG que chega ao
 * modelo a [MAX_RAG_TOKENS] sem resumir nada: só prioriza, deduplica e corta
 * por orçamento.
 *
 * Regras (nesta ordem):
 * 1. mantém a ordem ranqueada (mais relevante primeiro);
 * 2. preserva evidências completas (nunca corta no meio quando há alternativa);
 * 3. elimina redundância (texto normalizado duplicado);
 * 4. limita o total a aproximadamente [MAX_RAG_TOKENS] (estimativa
 *    [CHARS_PER_TOKEN]; só trunca com marcador quando UMA fonte sozinha
 *    estoura o orçamento e nada coube ainda).
 *
 * Os objetos [EvidenceSource] viajam inteiros — referência, publicação,
 * seção, parágrafo e página nunca se perdem aqui (a serialização continua
 * em `serializePack`).
 *
 * Puro/testável: sem Android, sem IO.
 */
object LeanRag {

    /** Meta de contexto RAG por geração (§4 F2.1). */
    const val MAX_RAG_TOKENS = 2000

    /** Estimativa grosseira p/ PT-BR; documentada como estimativa, não medida. */
    const val CHARS_PER_TOKEN = 4

    const val TRUNCATION_MARKER = " […]"

    fun estimateTokens(text: String): Int =
        (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** Tamanho orçado de uma fonte (texto + referência, como serializado). */
    fun sourceTokens(source: EvidenceSource): Int =
        estimateTokens(source.text) + estimateTokens(source.reference)

    fun compact(pack: ContextPack?, maxTokens: Int = MAX_RAG_TOKENS): ContextPack? {
        if (pack == null) return null
        if (maxTokens <= 0) return ContextPack(emptyList(), emptyList())
        var budget = maxTokens
        val content = take(pack.contentSources, budget, allowTruncate = true)
        budget -= content.sources.sumOf(::sourceTokens)
        // Trunca o treinamento só se NADA foi aproveitado (orçamento global).
        val training = take(pack.trainingSources, budget, allowTruncate = content.sources.isEmpty())
        if (!content.changed && !training.changed) return pack
        return ContextPack(content.sources, training.sources)
    }

    private data class TakeResult(val sources: List<EvidenceSource>, val changed: Boolean)

    private fun take(
        sources: List<EvidenceSource>,
        budgetTokens: Int,
        allowTruncate: Boolean,
    ): TakeResult {
        if (sources.isEmpty() || budgetTokens <= 0) {
            return TakeResult(if (sources.isEmpty()) sources else emptyList(), sources.isNotEmpty())
        }
        val out = mutableListOf<EvidenceSource>()
        val seen = HashSet<String>()
        var remaining = budgetTokens
        var changed = false
        for (source in sources) {
            val key = GroundednessVerifier.normalize(source.text)
            if (key.isBlank() || !seen.add(key)) {
                changed = true
                continue // redundância
            }
            val cost = sourceTokens(source)
            if (cost <= remaining) {
                out += source
                remaining -= cost
                if (remaining <= 0) break
            } else if (allowTruncate && out.isEmpty()) {
                // Só esta fonte e ela sozinha estoura: trunca com marcador
                // em vez de entregar contexto vazio.
                out += truncateTo(source, remaining)
                changed = true
                break
            } else {
                // Greedy estrito: para na primeira que não couber inteira
                // (ordem ranqueada = mais relevante primeiro).
                changed = true
                break
            }
        }
        return TakeResult(out, changed)
    }

    private fun truncateTo(source: EvidenceSource, budgetTokens: Int): EvidenceSource {
        val charBudget = (budgetTokens * CHARS_PER_TOKEN)
            .coerceAtLeast(TRUNCATION_MARKER.length + 1)
        val ref = source.reference
        val textBudget = (charBudget - ref.length).coerceAtLeast(1)
        val cut = source.text.take(textBudget)
        val trimmed = cut.dropLastWhile { !it.isWhitespace() }.ifBlank { cut }
        return source.copy(text = trimmed.trimEnd() + TRUNCATION_MARKER)
    }
}
