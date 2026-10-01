package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.GeminiProvider
import com.bettertalker.app.domain.planning.OutlineGenerationRequest
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.SectionPlan

/**
 * Converte o JSON textual retornado pelo LLM em [OutlineProposal].
 */
interface OutlineResponseParser {
    /**
     * Parseia [jsonText] no contexto de [request].
     *
     * Retorna null se o JSON for malformado, se faltarem campos obrigatórios
     * ou se houver tipos errados. Refs inventadas (fora do [request]) são
     * filtradas silenciosamente (anti-alucinação). id, angle, audience e
     * totalMinutes vêm do [request], nunca do JSON.
     */
    fun parse(jsonText: String, request: OutlineGenerationRequest): OutlineProposal?
}

/**
 * Parser manual no estilo [ChatCodec][com.bettertalker.app.data.util.ChatCodec]:
 * regex de extração + unescape + `try/catch → null`. Sem dependências novas
 * (org.json não existe nos testes JVM).
 *
 * Campos obrigatórios: title, summary, sections[]; por seção: title, minutes
 * (inteiro), mainIdea, bibleRefs[]. publicationRefs[] e methodPrinciple são
 * opcionais. Campos extras são ignorados. Wrapper markdown (```) é rejeitado.
 */
class DefaultOutlineResponseParser : OutlineResponseParser {

    override fun parse(jsonText: String, request: OutlineGenerationRequest): OutlineProposal? {
        return try {
            parseOrNull(jsonText, request)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOrNull(jsonText: String, request: OutlineGenerationRequest): OutlineProposal? {
        if (jsonText.contains("```")) return null
        val root = rootObject(jsonText) ?: return null
        val sectionsSpan = arraySpan(root, "sections") ?: return null
        val head = root.substring(0, sectionsSpan.start) + root.substring(sectionsSpan.end)
        val title = stringField(head, "title")?.ifBlank { return null } ?: return null
        val summary = stringField(head, "summary")?.ifBlank { return null } ?: return null
        val sections = splitObjects(root.substring(sectionsSpan.start, sectionsSpan.end))
            .map { parseSection(it, request) ?: return null }
        return OutlineProposal(
            id = request.id,
            angle = request.angle,
            audience = request.audience,
            title = title,
            summary = summary,
            totalMinutes = request.totalMinutes,
            sections = sections,
        )
    }

    private fun parseSection(raw: String, request: OutlineGenerationRequest): SectionPlan? {
        val title = stringField(raw, "title") ?: return null
        val minutes = intField(raw, "minutes") ?: return null
        val mainIdea = stringField(raw, "mainIdea") ?: return null
        val refs = stringArray(raw, "bibleRefs") ?: return null
        val pubs = objectArray(raw, "publicationRefs") ?: return null
        val method = stringField(raw, "methodPrinciple")
        val keptRefs = refs.filter { it in request.bibleRefs }
        val allowedSymbols = request.publicationRefs.map { it.symbol }.toSet()
        val keptPubs = pubs.mapNotNull { parsePublicationRef(it) }
            .filter { it.symbol in allowedSymbols }
        return SectionPlan(
            title = title,
            minutes = minutes,
            mainIdea = mainIdea,
            bibleRefs = keptRefs,
            publicationRefs = keptPubs,
            methodPrinciple = method?.ifBlank { null },
        )
    }

    private fun parsePublicationRef(raw: String): PublicationRef? {
        val symbol = stringField(raw, "symbol")?.ifBlank { return null } ?: return null
        return PublicationRef(
            symbol = symbol,
            page = intField(raw, "page"),
            paragraph = intField(raw, "paragraph"),
        )
    }

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

    /** Span [start, end) do conteúdo interno do array opcional da [key]; ausente = vazio, malformado = null. */
    private fun objectArray(scope: String, key: String): List<String>? {
        if (!Regex("\"$key\"\\s*:").containsMatchIn(scope)) return emptyList()
        val span = arraySpan(scope, key) ?: return null
        return splitObjects(scope.substring(span.start, span.end))
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

    private fun intField(scope: String, key: String): Int? {
        val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(scope) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** Divide o conteúdo de um array nos spans de cada objeto `{...}` de topo. */
    private fun splitObjects(arrayContent: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < arrayContent.length) {
            val start = arrayContent.indexOf('{', i)
            if (start < 0) break
            val end = matchClose(arrayContent, start, '{', '}') ?: break
            out.add(arrayContent.substring(start, end))
            i = end
        }
        return out
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
