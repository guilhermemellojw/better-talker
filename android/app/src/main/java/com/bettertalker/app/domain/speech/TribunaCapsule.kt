package com.bettertalker.app.domain.speech

/**
 * #20-fix-a / v4.2: cápsula da Tribuna — mapa de memória de UMA seção.
 *
 * Compatibilidade: o JSON antigo (mainIdea/anchorPhrase/bibleRef/transition)
 * continua lendo e renderizando; o código novo escreve também cues/keyTerms/
 * timeMinutes. Nunca há migração de `tribunaJson`.
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
    /** v4.2: duração alvo da seção (checkpoint do Palco). */
    val timeMinutes: Int? = null,
    /** v4.2: folha de cues; vazio = layout antigo. */
    val cues: List<TribunaCue> = emptyList(),
    /** v4.2: termos-chave para memorização. */
    val keyTerms: List<String> = emptyList(),
)

enum class TribunaCueKind {
    HOOK, REASONING, SCRIPTURE, ILLUSTRATION,
    APPLICATION, IMAGE, TRANSITION, INVITATION, RECAP, APPEAL
}

data class TribunaCue(
    val kind: TribunaCueKind,
    val text: String,
    val refs: List<String> = emptyList(),
    val imageId: String? = null,
    val imageCaption: String? = null,
)

fun TribunaCapsule.toJson(): String = buildString {
    append("{\"sectionId\":\"").append(tjEsc(sectionId)).append("\",")
    append("\"mainIdea\":\"").append(tjEsc(mainIdea)).append("\",")
    if (bibleRef != null) append("\"bibleRef\":\"").append(tjEsc(bibleRef)).append("\",")
    append("\"anchorPhrase\":\"").append(tjEsc(anchorPhrase)).append("\"")
    if (transition != null) append(",\"transition\":\"").append(tjEsc(transition)).append("\"")
    timeMinutes?.let { append(",\"timeMinutes\":").append(it) }
    if (keyTerms.isNotEmpty()) {
        append(",\"keyTerms\":[")
        append(keyTerms.joinToString(",") { "\"${tjEsc(it)}\"" })
        append("]")
    }
    if (cues.isNotEmpty()) {
        append(",\"cues\":[")
        append(cues.joinToString(",") { it.toJson() })
        append("]")
    }
    append("}")
}

private fun TribunaCue.toJson(): String = buildString {
    append("{\"kind\":\"").append(kind.name).append("\",")
    append("\"text\":\"").append(tjEsc(text)).append("\"")
    if (refs.isNotEmpty()) {
        append(",\"refs\":[")
        append(refs.joinToString(",") { "\"${tjEsc(it)}\"" })
        append("]")
    }
    imageId?.let { append(",\"imageId\":\"").append(tjEsc(it)).append("\"") }
    imageCaption?.let { append(",\"imageCaption\":\"").append(tjEsc(it)).append("\"") }
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
        timeMinutes = tjInt(json, "timeMinutes"),
        cues = tjCueList(json),
        keyTerms = tjStringArray(json, "keyTerms"),
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

private fun tjInt(obj: String, key: String): Int? =
    Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull()

/** Conteúdo interno de um array JSON simples (respeita strings/colchetes). */
private fun tjArray(obj: String, key: String): String? {
    val keyIdx = obj.indexOf("\"$key\"")
    if (keyIdx < 0) return null
    val start = obj.indexOf('[', keyIdx)
    if (start < 0) return null
    var depth = 0
    var inString = false
    var i = start
    while (i < obj.length) {
        val c = obj[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else {
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return obj.substring(start + 1, i)
                }
            }
        }
        i++
    }
    return null
}

private fun tjStringArray(obj: String, key: String): List<String> {
    val inner = tjArray(obj, key) ?: return emptyList()
    return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(inner)
        .map { tjUnesc(it.groupValues[1]) }
        .toList()
}

private fun tjCueList(obj: String): List<TribunaCue> {
    val inner = tjArray(obj, "cues") ?: return emptyList()
    return tjSplitObjects(inner).mapNotNull { cueObj ->
        val kind = tjString(cueObj, "kind")
            ?.let { runCatching { TribunaCueKind.valueOf(it) }.getOrNull() }
            ?: return@mapNotNull null
        val text = tjString(cueObj, "text") ?: return@mapNotNull null
        TribunaCue(
            kind = kind,
            text = text,
            refs = tjStringArray(cueObj, "refs"),
            imageId = tjString(cueObj, "imageId"),
            imageCaption = tjString(cueObj, "imageCaption"),
        )
    }
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
