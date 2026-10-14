package com.bettertalker.app.data.util

/**
 * #8c (GAP-2) — recorte de trecho da última resposta do chat para inserção
 * no card. Comandos:
 *
 * - "insira a opção 2" / "insira a 3" / "use a opção 2" → [Snippet.NthBlock]
 * - "insira o parágrafo 1"                              → [Snippet.NthParagraph]
 * - "insira de "X" a "Y""                               → [Snippet.Between]
 * - "insira só isso" / "insira apenas o texto"          → [Snippet.StripMeta]
 *
 * "insira isso" NÃO é recorte (retorna null) — segue o fluxo anafórico que
 * já existe. Puro/testável.
 */
sealed interface Snippet {
    /** N-ésimo bloco (separado por `---`, "**Opção N:**" ou "N."). */
    data class NthBlock(val n: Int) : Snippet

    /** N-ésimo parágrafo (separado por linha em branco). */
    data class NthParagraph(val n: Int) : Snippet

    /** Trecho literal entre dois marcadores citados. */
    data class Between(val start: String, val end: String) : Snippet

    /** Remove preâmbulo/pós-escrito "meta" (primeiro e último parágrafos). */
    object StripMeta : Snippet
}

private const val VERB = "(?:insira|insere|inserir|use|usar|coloque|colocar)"

private val OPCAO_RE = Regex(
    """\b$VERB\s+a\s+(?:op[cç][aã]o\s+)?(\d{1,2})\b""",
    RegexOption.IGNORE_CASE,
)
private val PARAGRAFO_RE = Regex(
    """\b$VERB\s+o\s+par[áa]grafo\s+(\d{1,2})\b""",
    RegexOption.IGNORE_CASE,
)
private val BETWEEN_RE = Regex(
    """\binsira\s+de\s+["“'](.+?)["”']\s+a\s+["“'](.+?)["”']""",
    RegexOption.IGNORE_CASE,
)
private val STRIP_META_RE = Regex(
    """\binsira\s+(s[óo]|apenas)\b""",
    RegexOption.IGNORE_CASE,
)

fun parseInsertSnippet(text: String): Snippet? {
    val t = text.trim()
    BETWEEN_RE.find(t)?.let { m ->
        val start = m.groupValues[1].trim()
        val end = m.groupValues[2].trim()
        if (start.isNotEmpty() && end.isNotEmpty()) return Snippet.Between(start, end)
    }
    PARAGRAFO_RE.find(t)?.let { m -> return Snippet.NthParagraph(m.groupValues[1].toInt()) }
    OPCAO_RE.find(t)?.let { m -> return Snippet.NthBlock(m.groupValues[1].toInt()) }
    if (STRIP_META_RE.containsMatchIn(t)) return Snippet.StripMeta
    return null
}

/** Rótulo humano do recorte (mensagem de falha do chat). */
fun snippetLabel(snippet: Snippet): String = when (snippet) {
    is Snippet.NthBlock -> "a opção ${snippet.n}"
    is Snippet.NthParagraph -> "o parágrafo ${snippet.n}"
    is Snippet.Between -> "o trecho"
    Snippet.StripMeta -> "o texto"
}

private val DASH_SEP = Regex("""\n\s*-{3,}\s*\n""")
private val OPCAO_MARK = Regex(
    """(?m)^\s*\*{0,2}\s*Op[cç][aã]o\s*\d+\s*:?\s*\*{0,2}.*$""",
    RegexOption.IGNORE_CASE,
)
private val NUMBERED_LINE = Regex("""(?m)^\s*\d{1,2}[.)]\s""")
private val PARAGRAPH_SEP = Regex("""\n\s*\n""")

/**
 * Extrai o trecho pedido da resposta. Null = não encontrou (o chat avisa e
 * NÃO insere). Puro/testável.
 */
fun extractSnippet(responseText: String, snippet: Snippet): String? {
    val text = responseText.trim()
    if (text.isEmpty()) return null
    return when (snippet) {
        is Snippet.NthBlock -> nthBlock(text, snippet.n)
        is Snippet.NthParagraph -> nthParagraph(text, snippet.n)
        is Snippet.Between -> between(text, snippet.start, snippet.end)
        Snippet.StripMeta -> stripMeta(text)
    }
}

/**
 * Blocos por: (1) separador `---`; (2) marcadores "**Opção N:**" (o rótulo é
 * removido do trecho); (3) linhas numeradas ("1. …", preservadas). Puro.
 */
private fun nthBlock(text: String, n: Int): String? {
    if (n < 1) return null
    if (DASH_SEP.containsMatchIn(text)) {
        val blocks = DASH_SEP.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        return blocks.getOrNull(n - 1)
    }
    val marks = OPCAO_MARK.findAll(text).toList()
    if (marks.isNotEmpty()) {
        val m = marks.getOrNull(n - 1) ?: return null
        val nl = text.indexOf('\n', m.range.last)
        if (nl < 0) return null
        val end = if (n < marks.size) marks[n].range.first else text.length
        return text.substring(nl + 1, end).trim().ifEmpty { null }
    }
    val lines = NUMBERED_LINE.findAll(text).toList()
    if (lines.isNotEmpty()) {
        val start = lines.getOrNull(n - 1)?.range?.first ?: return null
        val end = if (n < lines.size) lines[n].range.first else text.length
        return text.substring(start, end).trim().ifEmpty { null }
    }
    return null
}

private fun nthParagraph(text: String, n: Int): String? {
    if (n < 1) return null
    val paras = text.split(PARAGRAPH_SEP).map { it.trim() }.filter { it.isNotEmpty() }
    return paras.getOrNull(n - 1)
}

private fun between(text: String, start: String, end: String): String? {
    val m = Regex(
        Regex.escape(start) + "(.*?)" + Regex.escape(end),
        RegexOption.DOT_MATCHES_ALL,
    ).find(text) ?: return null
    return m.groupValues[1].trim().ifEmpty { null }
}

private val META_HINTS = listOf("claro", "aqui", "espero", "vou", "a seguir", "segue")

private fun isMetaParagraph(p: String): Boolean =
    p.length < 80 && META_HINTS.any { p.lowercase().contains(it) }

/** Remove o primeiro/último parágrafo quando são "meta" (cortesia/aviso). */
private fun stripMeta(text: String): String? {
    val paras = text.split(PARAGRAPH_SEP).map { it.trim() }.filter { it.isNotEmpty() }
    if (paras.isEmpty()) return null
    var lo = 0
    var hi = paras.size - 1
    if (paras.size > 1 && isMetaParagraph(paras[lo])) lo++
    if (hi >= lo && isMetaParagraph(paras[hi])) hi--
    return paras.subList(lo, hi + 1).joinToString("\n\n").trim().ifEmpty { null }
}
