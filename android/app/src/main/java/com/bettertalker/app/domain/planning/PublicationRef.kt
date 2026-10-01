package com.bettertalker.app.domain.planning

/**
 * Referência a uma publicação (ex.: "w19.03", "be", "th").
 *
 * @param symbol símbolo da publicação.
 * @param page página, quando conhecida.
 * @param paragraph parágrafo, quando conhecido.
 */
data class PublicationRef(
    val symbol: String,
    val page: Int? = null,
    val paragraph: Int? = null,
)
