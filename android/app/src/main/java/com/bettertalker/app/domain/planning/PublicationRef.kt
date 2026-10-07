package com.bettertalker.app.domain.planning

/**
 * Referência a uma publicação (ex.: "w19.03", "be", "th").
 *
 * @param symbol símbolo da publicação.
 * @param page página, quando conhecida.
 * @param paragraph parágrafo, quando conhecido.
 * @param article artigo/verbete citado (T2: "it "Gedalias" n.° 4").
 * @param chapter lição/capítulo/estudo citado (T2: "lmd lição 3 § 4").
 */
data class PublicationRef(
    val symbol: String,
    val page: Int? = null,
    val paragraph: Int? = null,
    val article: String? = null,
    val chapter: String? = null,
)
