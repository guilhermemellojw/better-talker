package com.bettertalker.app.data.copilot

/**
 * Flags do experimento de qualidade do chat (branch exp/chat-diagnostics).
 * NÃO é comportamento de produção: os defaults preservam o estado atual.
 */
data class ChatExperimentFlags(
    val injectDossierAlways: Boolean = true,
    val enableCardIntent: Boolean = true,
    val temperature: Float = 0.2f,
    val useFullHistory: Boolean = false,
    val includeMentorHint: Boolean = true,
    val useUnifiedPersona: Boolean = false,
) {
    companion object {
        val DEFAULT = ChatExperimentFlags()
    }
}

private val BIBLE_REF_RE =
    Regex("""\b(?:[1-3]\s*)?[a-zà-ÿ]+\.?\s+\d{1,3}:\d{1,3}\b""", RegexOption.IGNORE_CASE)
private val PUBLICATION_REF_RE =
    Regex("""\b(?:w|wp|g|gn|be|th|lff)\s*\d{2}(?:[./]\d{1,2})?\b""", RegexOption.IGNORE_CASE)
private val DOSSIER_KEYWORD_RE = Regex(
    """\b(se[cç][aã]o|t[oó]pico|ponto|dossi[eê]|esbo[cç]o|s-?34|publica[cç][aã]o|vers[ií]culo|texto\s+b[ií]blico|refer[eê]ncia|ref|li[cç][aã]o|introdu[cç][aã]o|conclus[aã]o)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * H1 — gate determinístico: pergunta genérica não precisa do dossiê; pergunta
 * que menciona ref/seção/publicação precisa. Puro/testável.
 */
fun shouldInjectDossier(message: String): Boolean {
    val text = message.trim()
    if (text.isEmpty()) return false
    return BIBLE_REF_RE.containsMatchIn(text) ||
        PUBLICATION_REF_RE.containsMatchIn(text) ||
        DOSSIER_KEYWORD_RE.containsMatchIn(text)
}
