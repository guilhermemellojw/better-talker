package com.bettertalker.app.data.db

import com.bettertalker.app.data.repo.decodeStringList
import com.bettertalker.app.data.repo.encodeStringList
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection

/**
 * Mapeamento entidade Room <-> domínio para seções de discurso.
 * Listas serializadas como JSON manual (padrão do projeto, sem dependências).
 */
fun SpeechSectionEntity.toDomain(): SpeechSection {
    return SpeechSection(
        id = id,
        noteId = noteId,
        order = order,
        role = runCatching { SectionRole.valueOf(role) }.getOrDefault(SectionRole.BODY),
        title = title,
        minutes = minutes,
        contentHtml = contentHtml,
        bibleRefs = decodeStringList(bibleRefsJson),
        publicationRefs = decodePublicationRefList(publicationRefsJson),
        methodPrinciple = methodPrinciple,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun SpeechSection.toEntity(): SpeechSectionEntity {
    return SpeechSectionEntity(
        id = id,
        noteId = noteId,
        order = order,
        role = role.name,
        title = title,
        minutes = minutes,
        contentHtml = contentHtml,
        bibleRefsJson = encodeStringList(bibleRefs),
        publicationRefsJson = encodePublicationRefList(publicationRefs),
        methodPrinciple = methodPrinciple,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/**
 * Codec de lista de PublicationRef. Formato:
 * `[{"symbol":"w21.08","page":18,"paragraph":13},...]`.
 * `symbol` obrigatório; `page`/`paragraph` omitidos quando null.
 * Ordem preservada; escapes como em [encodeStringList].
 */
fun encodePublicationRefList(items: List<PublicationRef>): String {
    return items.joinToString(",", "[", "]") { ref ->
        buildString {
            append("{\"symbol\":\"${escJson(ref.symbol)}\"")
            if (ref.page != null) append(",\"page\":${ref.page}")
            if (ref.paragraph != null) append(",\"paragraph\":${ref.paragraph}")
            append("}")
        }
    }
}

fun decodePublicationRefList(json: String): List<PublicationRef> {
    val objects = splitTopObjects(json)
    return objects.mapNotNull { obj ->
        val symbol = jsonStringField(obj, "symbol") ?: return@mapNotNull null
        PublicationRef(
            symbol = symbol,
            page = jsonIntField(obj, "page"),
            paragraph = jsonIntField(obj, "paragraph"),
        )
    }
}

private fun escJson(s: String): String {
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

private fun unescJson(s: String): String {
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

private fun jsonStringField(obj: String, key: String): String? {
    val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj) ?: return null
    return unescJson(m.groupValues[1])
}

private fun jsonIntField(obj: String, key: String): Int? {
    val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(obj) ?: return null
    return m.groupValues[1].toIntOrNull()
}

/** Divide array JSON nos objetos `{...}` de topo (respeita strings com escape). */
private fun splitTopObjects(json: String): List<String> {
    val t = json.trim()
    if (!t.startsWith("[") || !t.endsWith("]")) return emptyList()
    val out = mutableListOf<String>()
    var depth = 0
    var inString = false
    var start = -1
    var i = 1
    val end = t.length - 1
    while (i < end) {
        val c = t[i]
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
                        out.add(t.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        i++
    }
    return out
}
