package com.bettertalker.app.domain.speech

/** Nível de detalhe da folha de lembretes (persistido em DataStore). */
enum class TribunaDetail { COMPACT, STANDARD, DETAILED }

/** Regras editoriais/prompt do nível de detalhe. Puro. */
data class TribunaDetailRules(
    val minCues: Int,
    val maxCues: Int,
    val maxWords: Int,
    val minKeyTerms: Int,
    val maxKeyTerms: Int,
)

fun TribunaDetail.rules(): TribunaDetailRules = when (this) {
    TribunaDetail.COMPACT -> TribunaDetailRules(3, 5, 12, 3, 3)
    TribunaDetail.STANDARD -> TribunaDetailRules(4, 8, 20, 3, 6)
    TribunaDetail.DETAILED -> TribunaDetailRules(8, 12, 25, 5, 8)
}
