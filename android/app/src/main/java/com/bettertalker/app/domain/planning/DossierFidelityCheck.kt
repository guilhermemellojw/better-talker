package com.bettertalker.app.domain.planning

/**
 * Verifica se um draft (textHtml + usedSources) é fiel ao dossiê.
 *
 * Puro (só regex + comparação). Molde: OratoryFidelityCheck.
 *
 * Roda sobre o `textHtml` SEM strip — as tags HTML não interferem nos
 * regex de refs (mesmo precedente do OratoryFidelityCheck).
 *
 * NÃO é verificação profunda por claim (isso é o Verifier, fase posterior).
 * É triagem rápida: detecta invenções óbvias antes de mostrar ao usuário.
 */
object DossierFidelityCheck {

    /** Versículos: "Gên 3:6", "Sal 90:10", "1Co 15:22". */
    private val VERSE_RE = Regex(
        """\b([1-3]\s?)?([A-ZÁÉÍÓÚÂÊÔÃÕÇ][a-záéíóúâêôãõç]+)\s+(\d{1,3}):(\d{1,3})(?:[a-z])?""",
        RegexOption.IGNORE_CASE,
    )

    /** Publicações: "w21.08", "g 8/13", "w94 1/8", "it-1", "be". */
    private val PUB_RE = Regex(
        """\b(it-[1-3]|pe|rs|re|dp|dg|bh|jv|kj|mrt|ia|ifi|be|th|w\d{2}\.\d{2}|wp\d{2}\.\d{2}|g\s?\d{1,2}/\d{2}|gn\s?\d{1,2}/\d{2}|w\d{2}\s\d{1,2}/\d{1,2})\b""",
        RegexOption.IGNORE_CASE,
    )

    fun check(
        textHtml: String,
        usedSources: List<String>,
        dossier: Dossier,
    ): DossierFidelityReport {
        val allowedVerses = dossier.bibleTexts.map { normalizeRef(it.ref) }.toSet()
        val allowedPubs = dossier.publicationTexts.map { normalizeRef(it.ref.symbol) }.toSet()
        val unresolvedNormalized = dossier.unresolvedRefs.map { normalizeRef(it) }.toSet()

        val versesInText = VERSE_RE.findAll(textHtml)
            .map { it.value }
            .map { normalizeRef(it) }
            .distinct()
            .toList()
        val pubsInText = PUB_RE.findAll(textHtml)
            .map { it.value }
            .map { normalizeRef(it) }
            .distinct()
            .toList()

        val inventedBible = versesInText.filter { it !in allowedVerses }
        val inventedPub = pubsInText.filter { it !in allowedPubs }

        val allowedAll = allowedVerses + allowedPubs
        val usedOutside = usedSources
            .map { normalizeUsedSource(it) }
            .filter { it !in allowedAll }
            .distinct()

        val unresolvedCited = unresolvedNormalized
            .filter { ref ->
                versesInText.any { it.contains(ref) } || pubsInText.any { it.contains(ref) }
            }
            .distinct()

        return DossierFidelityReport(
            inventedBibleRefs = inventedBible,
            inventedPublicationRefs = inventedPub,
            usedSourcesOutsideDossier = usedOutside,
            unresolvedRefsCited = unresolvedCited,
        )
    }

    /** Normaliza ref para comparação: lowercase + collapse de espaços. */
    private fun normalizeRef(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Normaliza uma entrada de usedSources removendo sufixos de página/
     * parágrafo que o LLM pode incluir.
     *
     * Exemplos:
     * - "be p. 52 §3"      → "be"
     * - "w21.08 §13"       → "w21.08"
     * - "g 8/13 p. 4"      → "g 8/13"
     * - "Gên 3:6"          → "gên 3:6" (inalterado — ref bíblica)
     */
    private fun normalizeUsedSource(raw: String): String {
        return normalizeRef(raw)
            .replace(Regex("""\s+p\.?\s*\d+.*$"""), "")
            .replace(Regex("""\s+§\s*\d+.*$"""), "")
            .replace(Regex("""\s+pag\.?\s*\d+.*$"""), "")
            .replace(Regex("""\s+par\.?\s*\d+.*$"""), "")
            .trim()
    }
}
