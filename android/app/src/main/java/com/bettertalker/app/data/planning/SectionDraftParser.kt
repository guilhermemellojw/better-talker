package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.JsonCodec

/** Resultado do parse do JSON retornado pelo LLM. */
data class ParsedDraft(
    val textHtml: String,
    val usedSources: List<String>,
    /** true quando o modelo parou por limite de tamanho (`finish_reason`). */
    val possiblyTruncated: Boolean = false,
)

interface SectionDraftParser {
    fun parse(jsonText: String): ParsedDraft?

    /**
     * Overload aditiva: carrega o `finish_reason` do provider para o
     * flag de truncamento. Default delega sem motivo (= não truncado).
     */
    fun parse(jsonText: String, finishReason: String?): ParsedDraft? = parse(jsonText)

    /**
     * Parsing tolerante para uma PARTE que o provider cortou no meio do JSON:
     * extrai o campo `text` até onde deu, marca truncado e devolve o parcial.
     * O parser estrito continua rejeitando JSON malformado.
     */
    fun parsePartial(jsonText: String, finishReason: String? = null): ParsedDraft? = null
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

    override fun parse(jsonText: String): ParsedDraft? = parse(jsonText, null)

    override fun parse(jsonText: String, finishReason: String?): ParsedDraft? {
        return try {
            parseOrNull(jsonText, finishReason)
        } catch (_: Exception) {
            null
        }
    }

    override fun parsePartial(jsonText: String, finishReason: String?): ParsedDraft? {
        if (jsonText.contains("```")) return null
        val text = partialTextField(jsonText)?.takeIf { it.isNotBlank() } ?: return null
        return ParsedDraft(text, emptyList(), possiblyTruncated = true)
    }

    /** Extrai `"text":"..."` mesmo sem fechar a string/objeto. */
    private fun partialTextField(scope: String): String? {
        val keyIdx = scope.indexOf("\"text\"")
        if (keyIdx < 0) return null
        var i = keyIdx + 6
        while (i < scope.length && scope[i].isWhitespace()) i++
        if (i >= scope.length || scope[i] != ':') return null
        i++
        while (i < scope.length && scope[i].isWhitespace()) i++
        if (i >= scope.length || scope[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < scope.length) {
            val c = scope[i]
            if (c == '\\' && i + 1 < scope.length) {
                when (val next = scope[i + 1]) {
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 5 < scope.length) {
                            val code = scope.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(next)
                    }
                    else -> out.append(next)
                }
                i += 2
                continue
            }
            if (c == '"') return out.toString()
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun parseOrNull(jsonText: String, finishReason: String?): ParsedDraft? {
        if (jsonText.contains("```")) return null
        val root = rootObject(jsonText) ?: return null
        val text = stringField(root, "text")?.takeIf { it.isNotBlank() } ?: return null
        val usedSources = stringArray(root, "usedSources") ?: emptyList()
        return ParsedDraft(text, usedSources, possiblyTruncated = finishReason == "length")
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

    /**
     * Parsing linear de string JSON. O regex `(?:[^"\\]|\\.)*` estoura a
     * pilha em drafts de 45 min (~20 KB em uma string); aqui não há regex.
     */
    private fun stringField(scope: String, key: String): String? {
        val keyIdx = scope.indexOf("\"$key\"")
        if (keyIdx < 0) return null
        var i = keyIdx + key.length + 2
        while (i < scope.length && scope[i].isWhitespace()) i++
        if (i >= scope.length || scope[i] != ':') return null
        i++
        while (i < scope.length && scope[i].isWhitespace()) i++
        if (i >= scope.length || scope[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < scope.length) {
            val c = scope[i]
            if (c == '\\' && i + 1 < scope.length) {
                when (val next = scope[i + 1]) {
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        if (i + 5 < scope.length) {
                            val code = scope.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(next)
                    }
                    else -> out.append(next)
                }
                i += 2
                continue
            }
            if (c == '"') return out.toString()
            out.append(c)
            i++
        }
        return null
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

    private fun unesc(raw: String): String = JsonCodec.unescape(raw)

    private data class TextSpan(val start: Int, val end: Int)

    private companion object {
        val STRING_ITEM = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
