package com.bettertalker.app.domain.speech

/**
 * #20-fix-a: cápsula da Tribuna — mapa de memória de UMA seção. Não é resumo:
 * é o que o orador precisa para falar de coração (ideia + âncora + ponte).
 * Serialização JSON manual (padrão do projeto — sem dependências de camada).
 */
data class TribunaCapsule(
    val sectionId: String,
    /** 1 frase: o que esta seção diz. */
    val mainIdea: String,
    /** Ref mais importante da seção (ex.: "Sal 103:20"); null quando não há. */
    val bibleRef: String? = null,
    /** Frase-chave para memorizar (vem do TEXTO da seção). */
    val anchorPhrase: String,
    /** Como liga para a próxima seção; null na última. */
    val transition: String? = null,
)

fun TribunaCapsule.toJson(): String = buildString {
    append("{\"sectionId\":\"").append(tjEsc(sectionId)).append("\",")
    append("\"mainIdea\":\"").append(tjEsc(mainIdea)).append("\",")
    if (bibleRef != null) append("\"bibleRef\":\"").append(tjEsc(bibleRef)).append("\",")
    append("\"anchorPhrase\":\"").append(tjEsc(anchorPhrase)).append("\"")
    if (transition != null) append(",\"transition\":\"").append(tjEsc(transition)).append("\"")
    append("}")
}

fun List<TribunaCapsule>.toJson(): String = joinToString(",", "[", "]") { it.toJson() }

/** #20-fix-a: parse de UMA cápsula; null quando o JSON não é utilizável. Puro. */
fun tribunaCapsuleFromJson(json: String): TribunaCapsule? {
    val id = tjString(json, "sectionId") ?: return null
    val idea = tjString(json, "mainIdea") ?: return null
    val anchor = tjString(json, "anchorPhrase") ?: return null
    return TribunaCapsule(
        sectionId = id,
        mainIdea = idea,
        bibleRef = tjString(json, "bibleRef"),
        anchorPhrase = anchor,
        transition = tjString(json, "transition"),
    )
}

/**
 * #20-fix-a: parse da lista (array JSON de cápsulas). Itens inválidos são
 * pulados (nunca crash). Puro/testável.
 */
fun tribunaCapsulesFromJson(json: String): List<TribunaCapsule> {
    val t = json.trim()
    val inner = if (t.startsWith("[") && t.endsWith("]")) t.substring(1, t.length - 1) else t
    return tjSplitObjects(inner).mapNotNull { tribunaCapsuleFromJson(it) }
}

// ---------- codec JSON mínimo (mesmo padrão do SpeechSectionMapper) ----------

private fun tjEsc(s: String): String {
    val sb = StringBuilder()
    for (c in s) when (c) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> sb.append(c)
    }
    return sb.toString()
}

private fun tjUnesc(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                else -> sb.append(s[i + 1])
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun tjString(obj: String, key: String): String? {
    val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj) ?: return null
    return tjUnesc(m.groupValues[1])
}

/** Divide array JSON nos objetos `{...}` de topo (respeita strings com escape). */
private fun tjSplitObjects(inner: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var inString = false
    var start = -1
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (inString) {
            if (c == '\\') i++
            else if (c == '"') inString = false
        } else {
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(inner.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        i++
    }
    return out
}
