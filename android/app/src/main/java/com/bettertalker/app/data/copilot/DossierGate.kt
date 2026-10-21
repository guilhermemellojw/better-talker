package com.bettertalker.app.data.copilot

/**
 * exp/chat-quality (2026-10-21) — H1: o dossiê (bloco do S-34, textos
 * bíblicos, trechos de publicações) só entra no prompt quando a pergunta fala
 * do conteúdo: referência bíblica/publicação, seção, tópico ou esboço.
 *
 * Medido no A/B com o system prompt real do app: na pergunta genérica o
 * dossiê não mudava a resposta (196 → 174 palavras) e só aumentava o prompt;
 * na pergunta que cita ref/seção ele continua entrando (é o que permite citar
 * o texto e escrever o card).
 *
 * Puro/testável.
 */
private val BIBLE_REF_RE =
    Regex("""\b(?:[1-3]\s*)?[a-zà-ÿ]+\.?\s+\d{1,3}:\d{1,3}\b""", RegexOption.IGNORE_CASE)

private val PUBLICATION_REF_RE =
    Regex("""\b(?:w|wp|g|gn|be|th|lff)\s*\d{2}(?:[./]\d{1,2})?\b""", RegexOption.IGNORE_CASE)

private val DOSSIER_KEYWORD_RE = Regex(
    """\b(se[cç][aã]o|t[oó]pico|ponto|dossi[eê]|esbo[cç]o|s-?34|publica[cç][aã]o|vers[ií]culo|""" +
        """texto\s+b[ií]blico|refer[eê]ncia|ref|li[cç][aã]o|introdu[cç][aã]o|conclus[aã]o|""" +
        """discurso|discurso|t[oó]picos|trecho)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * O dossiê deve entrar? Verdadeiro quando a mensagem menciona referência
 * bíblica/publicação, seção, tópico, esboço ou o próprio discurso.
 */
fun shouldInjectDossier(message: String): Boolean {
    val text = message.trim()
    if (text.isEmpty()) return false
    return BIBLE_REF_RE.containsMatchIn(text) ||
        PUBLICATION_REF_RE.containsMatchIn(text) ||
        DOSSIER_KEYWORD_RE.containsMatchIn(text)
}
