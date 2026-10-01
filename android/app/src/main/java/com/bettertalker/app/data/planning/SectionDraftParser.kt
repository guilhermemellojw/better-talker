package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.GeminiProvider

/** Resultado do parse do JSON retornado pelo LLM. */
data class ParsedDraft(
    val textHtml: String,
    val usedSources: List<String>,
)

interface SectionDraftParser {
    fun parse(jsonText: String): ParsedDraft?
}

/**
 * Parser manual (molde: DefaultOutlineResponseParser).
 *
 * - Rejeita wrapper markdown (```).
 * - Exige "text" (string não-vazia).
 * - "usedSources" é opcional (default [] se ausente); se presente, deve
 *   ser array de strings.
 * - Sem org.json/kotlinx.serialization (JVM tests não têm).
 *
 * DÉBITO (3.5e.1): os helpers de JSON (rootObject/arraySpan/stringField/
 * stringArray/matchClose) são duplicados do DefaultOutlineResponseParser —
 * unificar num helper comum quando houver folga (menor risco agora).
 */
class DefaultSectionDraftParser : SectionDraftParser {

    override fun parse(jsonText: String): ParsedDraft? {
        return try {
            parseOrNull(jsonText)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOrNull(jsonText: String): ParsedDraft? {
        if (jsonText.contains("```")) return null
        val root = rootObject(jsonText) ?: return null
        val text = stringField(root, "text")?.takeIf { it.isNotBlank() } ?: return null
        val usedSources = stringArray(root, "usedSources") ?: emptyList()
        return ParsedDraft(text, usedSources)
    }

    // ---------- helpers manuais (padrão do OutlineResponseParser) ----------

    /** Objeto raiz: do primeiro `{` ao seu `}` correspondente. */
    private fun rootObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = matchClose(text, start, '{', '}') ?: return null
        return text.substring(start, end)
    }

    /** Span [start, end) do conteúdo interno do array da [key] (sem os colchetes). */
    private fun arraySpan(scope: String, key: String): TextSpan? {
        val m = Regex("\"$key\"\\s*:\\s*\\[").find(scope) ?: return null
        val open = m.range.last
        val close = matchClose(scope, open, '[', ']') ?: return null
        return TextSpan(open + 1, close)
    }

    private fun stringArray(scope: String, key: String): List<String>? {
        val span = arraySpan(scope, key) ?: return null
        return STRING_ITEM.findAll(scope.substring(span.start, span.end))
            .map { unesc(it.groupValues[1]) }
            .toList()
    }

    private fun stringField(scope: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(scope) ?: return null
        return unesc(m.groupValues[1])
    }

    /** Índice após o fechamento correspondente a [open]; respeita strings com escape. */
    private fun matchClose(text: String, open: Int, openCh: Char, closeCh: Char): Int? {
        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    openCh -> depth++
                    closeCh -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return null
    }

    private fun unesc(raw: String): String = GeminiProvider.jsonUnescape(raw)

    private data class TextSpan(val start: Int, val end: Int)

    private companion object {
        val STRING_ITEM = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
