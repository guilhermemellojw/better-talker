package com.bettertalker.app.data.domain

import com.bettertalker.app.data.util.normalizeText

/**
 * #15d — Expansão de query para o RAG: sinônimos CURADOS a partir do
 * vocabulário REAL do acervo (verbetes do `it` confirmados no índice do
 * device: MORTE, ALMA, SEOL, SONO, RESSURREIÇÃO, ESPERANÇA, RESGATE,
 * CURA, ANSIEDADE, MEDO, ORAÇÃO, PACIÊNCIA, PARAÍSO, REINO, FELICIDADE...).
 *
 * Chaves em forma normalizada (minúsculas, sem acento — casam com
 * [normalizeText], que é como a query chega ao retrieval).
 *
 * Usado APENAS no fallback adaptativo (quando o retrieve padrão devolve
 * menos de MIN_RELEVANT_HITS trechos relevantes). NUNCA substitui os
 * termos originais — adiciona.
 */
internal val QUERY_SYNONYMS: Map<String, List<String>> = mapOf(
    // morte / ressurreição — o caso "mortos inconscientes" → verbete MORTE
    "morto" to listOf("morte", "ressurreição", "alma", "seol"),
    "mortos" to listOf("morte", "ressurreição", "alma", "seol"),
    "morrer" to listOf("morte", "ressurreição", "alma"),
    "morte" to listOf("ressurreição", "alma", "seol"),
    "inconsciente" to listOf("morte", "alma", "sono"),
    "inconscientes" to listOf("morte", "alma", "sono"),
    "dormir" to listOf("morte", "sono", "ressurreição"),
    "sono" to listOf("morte", "ressurreição", "alma"),
    "ressuscitar" to listOf("ressurreição", "morte", "vida"),
    "ressuscitou" to listOf("ressurreição", "morte", "vida"),
    // esperança / vida eterna — temas reais das notas do usuário
    "esperanca" to listOf("paraíso", "reino", "ressurreição"),
    "eterna" to listOf("vida", "ressurreição", "paraíso"),
    // perdão / pecado
    "perdao" to listOf("perdoar", "arrependimento", "resgate"),
    "pecado" to listOf("perdão", "arrependimento", "resgate"),
    // sofrimento / ansiedade
    "sofrimento" to listOf("paciência", "morte", "ressurreição"),
    "ansiedade" to listOf("medo", "oração", "paciência"),
    // doença / cura
    "doenca" to listOf("cura", "ressurreição", "morte"),
    // vida prática
    "orar" to listOf("oração"),
    "casamento" to listOf("família", "amor"),
    "dinheiro" to listOf("trabalho"),
)

/**
 * Expande a query com os sinônimos curados: original + sinônimos das
 * palavras reconhecidas (deduplicados, ordem estável). NUNCA remove os
 * termos originais. Puro/testável.
 */
internal fun expandQuery(query: String): String {
    val base = query.trim()
    if (base.isEmpty()) return base
    val present = normalizeText(base).split(" ").filter { it.isNotBlank() }.toSet()
    val extra = LinkedHashSet<String>()
    for (word in present) {
        QUERY_SYNONYMS[word]?.forEach { synonym ->
            if (normalizeText(synonym) !in present) extra += synonym
        }
    }
    if (extra.isEmpty()) return base
    return base + " " + extra.joinToString(" ")
}
