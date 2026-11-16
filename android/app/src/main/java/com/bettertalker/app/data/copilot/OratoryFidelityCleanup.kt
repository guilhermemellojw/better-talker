package com.bettertalker.app.data.copilot

/**
 * Remove do texto gerado as sentenças que citam referências/números sem
 * apoio. O card nunca deve ser publicado com uma ref inventada; em vez de
 * mostrar um aviso e manter o texto, o conteúdo ofensor sai.
 *
 * Puro/testável: não conhece LLM, UI ou banco.
 */
internal fun removeUnsupportedFidelitySentences(
    text: String,
    report: OratoryFidelityCheck.Report,
): String {
    val tokens = (report.inventedReferences + report.leakedReferences + report.unsupportedNumbers)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    if (tokens.isEmpty()) return text

    fun unsupported(segment: String): Boolean =
        tokens.any { token -> segment.contains(token, ignoreCase = true) }

    // Mantém a estrutura de parágrafos; dentro de cada linha, remove só as
    // sentenças ofensoras. Uma linha sem pontuação e com ref cai inteira.
    val kept = text.lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull line
        val cleaned = line
            .split(Regex("(?<=[.!?…])\\s+"))
            .filterNot(::unsupported)
            .joinToString(" ")
            .trim()
        cleaned.ifBlank { null }
    }
    return kept.joinToString("\n").trim()
}
