package com.bettertalker.app.data.domain

import com.bettertalker.app.data.util.normalizeText

/**
 * Retrieval híbrido puro — mesmos princípios da web (Fases 3 e 7):
 * escopo-primeiro, TF-IDF lexical + score de metadata separado, rerank
 * ponderado, desempate determinístico, boost de categoria documentado.
 * Score = relevância em [0,1], nunca verdade. Sem Room, sem Android.
 */

data class ScoredCandidate(
    val passage: Passage,
    val publicationTitle: String?,
    val lexicalScore: Double,
    val metadataScore: Double,
    val finalScore: Double,
    val matchedTerms: List<String>,
    val foundBy: List<String>
)

object HybridRetrieval {

    /** Mesmos pesos da web (Fase 3). Ajustar só com evidência de benchmark. */
    const val LEXICAL_WEIGHT = 0.40
    const val METADATA_WEIGHT = 0.20
    const val SEMANTIC_WEIGHT = 0.30
    const val SOURCE_WEIGHT = 0.10

    /** Mesmo boost da web (Fase 7): reordena sem tocar no finalScore. */
    const val CATEGORY_MATCH_BOOST = 0.15

    private const val LEXICAL_MIN = 0.0 // admissão exige ao menos 1 estratégia com sinal

    /**
     * Stopwords do domínio de retrieval (autocontidas aqui de propósito:
     * a camada de domínio não depende de listas de outros módulos).
     * Espelham as da web (Fase 3), já sem acentos para casar normalizeText.
     */
    private val STOPWORDS = setOf(
        "o", "a", "os", "as", "um", "uma", "uns", "umas",
        "de", "da", "do", "das", "dos", "em", "no", "na", "nos", "nas",
        "que", "com", "para", "por", "e", "se", "nao", "como", "mais",
        "mas", "foi", "sao", "tem", "ter", "ser", "este", "esta",
        "esse", "essa", "isso", "isto", "voce", "ele", "ela", "eles",
        "elas", "seu", "sua", "meu", "minha", "quando", "onde", "qual",
        "quais", "porque", "entre", "sobre", "muito", "cada", "outro",
        "outra", "todo", "toda", "todos"
    )

    private val SOURCE_PRIORITY = mapOf(
        SourceType.BIBLE to 1.0,
        SourceType.CONTENT to 0.9,
        SourceType.TRAINING to 0.9
    )

    fun tokenize(text: String): List<String> =
        normalizeText(text).split(" ").filter { it.length >= 3 && it !in STOPWORDS }

    private fun idf(term: String, docFreq: Map<String, Int>, docCount: Int): Double {
        val df = docFreq[term] ?: 0
        // Termo ausente do conjunto não discrimina: contribui 0 (web Fase 6).
        if (df == 0 || docCount <= 0) return 0.0
        return (kotlin.math.ln(1.0 + docCount.toDouble() / (1 + df)) /
            kotlin.math.ln(1.0 + docCount.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun countOccurrences(haystack: String, term: String): Int {
        if (term.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(term)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(term, idx + term.length)
        }
        return count
    }

    private fun lexicalScore(
        queryTerms: List<String>,
        passage: Passage,
        docFreq: Map<String, Int>,
        docCount: Int
    ): Pair<Double, List<String>> {
        if (queryTerms.isEmpty()) return 0.0 to emptyList()
        val text = passage.normalizedText.ifBlank { normalizeText(passage.text) }
        val aux = normalizeText("${passage.section} ${passage.ref}")
        val haystack = "$text $aux".trim()
        var weighted = 0.0
        var weightSum = 0.0
        val matched = mutableListOf<String>()
        for (term in queryTerms.distinct()) {
            val w = idf(term, docFreq, docCount)
            weightSum += w
            val tf = countOccurrences(haystack, term)
            if (tf > 0) {
                matched += term
                weighted += w * minOf(1.0, 0.7 + 0.3 * minOf(tf, 4) / 4.0)
            }
        }
        val coverage = if (weightSum > 0) weighted / weightSum else 0.0
        return (0.7 * coverage).coerceIn(0.0, 1.0) to matched.sorted()
    }

    /** Bônus de frase exata (query normalizada contida no texto). */
    private fun exactBonus(normalizedQuery: String, passage: Passage): Double {
        if (normalizedQuery.length < 12) return 0.0
        val text = passage.normalizedText.ifBlank { normalizeText(passage.text) }
        return if (text.contains(normalizedQuery)) 0.3 else 0.0
    }

    private fun metadataScore(
        queryTerms: List<String>,
        normalizedQuery: String,
        passage: Passage,
        publicationTitle: String?
    ): Double {
        if (queryTerms.isEmpty()) return 0.0
        var score = 0.0
        val symbol = (passage.symbol ?: "").lowercase()
        if (symbol.isNotEmpty() && normalizedQuery.split(" ").contains(symbol)) score += 0.45
        score += 0.20 * overlap(queryTerms, tokenize(publicationTitle.orEmpty()).distinct())
        score += 0.20 * overlap(queryTerms, tokenize(passage.section).distinct())
        val refTerms = tokenize(passage.ref).distinct()
        if (refTerms.isNotEmpty()) {
            val overlapRef = overlap(refTerms, queryTerms)
            score += if (overlapRef >= 0.5) 0.15 else 0.15 * overlapRef * 0.5
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun overlap(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val set = b.toSet()
        return a.count { it in set }.toDouble() / a.size
    }

    /**
     * Ranqueia candidatos JÁ restritos ao escopo. Não admite passage sem
     * nenhum sinal (fonte só impulsiona). Ordem determinística.
     */
    fun rank(
        query: String,
        candidates: List<Passage>,
        titles: Map<String, String?>,
        limit: Int
    ): List<ScoredCandidate> {
        val queryTerms = tokenize(query).distinct()
        val normalizedQuery = normalizeText(query)
        if (queryTerms.isEmpty() || candidates.isEmpty() || limit <= 0) return emptyList()
        val docFreq = mutableMapOf<String, Int>()
        val norms = candidates.map { it.normalizedText.ifBlank { normalizeText(it.text) } }
        for (norm in norms) {
            for (t in tokenize(norm).toSet()) docFreq[t] = (docFreq[t] ?: 0) + 1
        }
        val out = mutableListOf<ScoredCandidate>()
        for (p in candidates) {
            val (lex, matched) = lexicalScore(queryTerms, p, docFreq, candidates.size)
            val lexTotal = (lex + exactBonus(normalizedQuery, p)).coerceIn(0.0, 1.0)
            val meta = metadataScore(queryTerms, normalizedQuery, p, titles[p.pubId])
            if (lexTotal <= LEXICAL_MIN && meta <= 0.0) continue // sem sinal: fora
            val foundBy = buildList {
                if (lexTotal > 0) add("lexical")
                if (meta > 0) add("metadata")
            }
            val source = SOURCE_PRIORITY[p.sourceType] ?: 0.5
            val final = (LEXICAL_WEIGHT * lexTotal + METADATA_WEIGHT * meta +
                SEMANTIC_WEIGHT * 0.0 + SOURCE_WEIGHT * source).coerceIn(0.0, 1.0)
            out += ScoredCandidate(p, titles[p.pubId], lexTotal, meta, final, matched, foundBy)
        }
        return out.sortedWith(
            compareByDescending<ScoredCandidate> { it.finalScore }
                .thenByDescending { it.lexicalScore }
                .thenByDescending { it.metadataScore }
                .thenBy { it.passage.id }
        ).take(limit.coerceAtLeast(0))
    }

    /**
     * Bônus de intenção (Fase 7): reordena sem tocar nos scores.
     * Determinístico; itens fora da categoria permanecem, depois.
     */
    fun applyCategoryBoost(
        hits: List<RetrievalCandidate>,
        category: TrainingCategory,
        resolve: (RetrievalCandidate) -> TrainingCategory
    ): List<RetrievalCandidate> {
        data class Adj(val adjusted: Double, val hit: RetrievalCandidate)
        return hits.map { h ->
            Adj(h.finalScore + if (resolve(h) == category) CATEGORY_MATCH_BOOST else 0.0, h)
        }.sortedWith(
            compareByDescending<Adj> { it.adjusted }
                .thenByDescending { it.hit.finalScore }
                .thenBy { it.hit.passage.id }
        ).map { it.hit }
    }
}
