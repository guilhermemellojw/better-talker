package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.JsonCodec
import com.bettertalker.app.data.s34.normalizeBibleBookName
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.stripPromptTags
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
        // F5f (E2E): o modelo às vezes embrulha o texto em tags próprias
        // ("<sugestão>…</sugestão>") — sanitizar antes de aceitar o campo.
        val title = stringField(head, "title")?.let { stripPromptTags(it) }
            ?.ifBlank { return null } ?: return null
        val summary = stringField(head, "summary")?.let { stripPromptTags(it) }
            ?.ifBlank { return null } ?: return null
        val sections = splitObjects(root.substring(sectionsSpan.start, sectionsSpan.end))
            .map { parseSection(it, request) ?: return null }
        val repaired = if (request.sectionScoped) {
            repairSectionScoped(sections, request)
        } else {
            sections
        }
        return OutlineProposal(
            id = request.id,
            angle = request.angle,
            audience = request.audience,
            title = title,
            summary = summary,
            totalMinutes = request.totalMinutes,
            sections = repaired,
        )
    }

    private fun parseSection(raw: String, request: OutlineGenerationRequest): SectionPlan? {
        // F5f (E2E): campos de TEXTO passam por stripPromptTags; refs
        // (estruturadas) não.
        val title = stringField(raw, "title")?.let { stripPromptTags(it) } ?: return null
        val minutes = intField(raw, "minutes") ?: return null
        val mainIdea = stringField(raw, "mainIdea")?.let { stripPromptTags(it) } ?: return null
        val refs = stringArray(raw, "bibleRefs") ?: return null
        val pubs = objectArray(raw, "publicationRefs") ?: return null
        val method = stringField(raw, "methodPrinciple")?.let { stripPromptTags(it) }
        // O modelo varia a grafia ("Ezequiel 1:16" vs "Ez 1:16"); casar por
        // livro/capítulo/versículo canônico antes de descartar como inventada.
        val keptRefs = refs
            .flatMap { matchRequestBibleRefs(it, request.bibleRefs) }
            .distinct()
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

    /** Casa a ref do modelo com a lista autorizada por livro/cap/versículo. */
    private fun matchRequestBibleRefs(
        raw: String,
        allowed: List<String>,
    ): List<String> {
        val keys = RefDetector.detectBible(raw)
            .mapNotNull { bibleKey(it.label, it.chapter, it.verse) }
            .toSet()
        if (keys.isEmpty()) return emptyList()
        return allowed.filter { candidate ->
            RefDetector.detectBible(candidate).any {
                bibleKey(it.label, it.chapter, it.verse) in keys
            }
        }
    }

    private fun bibleKey(label: String, chapter: Int, verse: Int): String? {
        val book = normalizeBibleBookName(label) ?: return null
        return "$book $chapter:$verse"
    }

    /**
     * Reparos só para estrutura de UMA seção: refs autoritativas, títulos
     * únicos e soma de minutos dentro do total. Evita o falso "estrutura
     * incompleta" sem afetar o esboço do zero.
     */
    private fun repairSectionScoped(
        sections: List<SectionPlan>,
        request: OutlineGenerationRequest,
    ): List<SectionPlan> {
        if (sections.isEmpty()) return sections
        var out = sections.map { it.copy(minutes = it.minutes.coerceAtLeast(1)) }
        val total = request.totalMinutes
        // O modelo pode devolver mais seções que minutos disponíveis (ou
        // minutos 0). Funde o excedente para manter 1 min por seção e a soma
        // dentro do total, em vez de falhar como "estrutura incompleta".
        if (total > 0 && out.size > total) {
            val keep = out.take(total - 1)
            val merged = out.drop(total - 1).reduce { acc, section ->
                acc.copy(
                    title = "${acc.title} / ${section.title}",
                    mainIdea = listOf(acc.mainIdea, section.mainIdea)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                    bibleRefs = (acc.bibleRefs + section.bibleRefs).distinct(),
                    publicationRefs = (acc.publicationRefs + section.publicationRefs)
                        .distinct(),
                    methodPrinciple = listOfNotNull(
                        acc.methodPrinciple, section.methodPrinciple,
                    ).filter { it.isNotBlank() }.distinct().joinToString(" ")
                        .ifBlank { null },
                )
            }
            out = (keep + merged).map { it.copy(minutes = 1) }
        }
        if (request.bibleRefs.isNotEmpty() &&
            out.none { it.bibleRefs.isNotEmpty() }
        ) {
            val want = request.theme.trim().lowercase()
            val target = out.indexOfFirst { section ->
                val title = section.title.trim().lowercase()
                title.isNotBlank() && (title.contains(want) || want.contains(title))
            }.takeIf { it >= 0 } ?: 0
            out = out.mapIndexed { index, section ->
                if (index == target) {
                    section.copy(bibleRefs = request.bibleRefs)
                } else {
                    section
                }
            }
        }
        if (out.size <= total && out.sumOf { it.minutes } > total) {
            var remaining = total
            out = out.mapIndexed { index, section ->
                val left = out.size - index
                val maxForThis = (remaining - (left - 1)).coerceAtLeast(1)
                val minutes = section.minutes.coerceIn(1, maxForThis)
                remaining -= minutes
                section.copy(minutes = minutes)
            }
        }
        val usedTitles = mutableSetOf<String>()
        return out.map { section ->
            val base = section.title.trim().ifBlank { "Seção" }
            var title = base
            var n = 2
            while (!usedTitles.add(title.lowercase())) {
                title = "$base ${n++}"
            }
            section.copy(title = title)
        }
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

    private fun unesc(raw: String): String = JsonCodec.unescape(raw)

    private data class TextSpan(val start: Int, val end: Int)

    private companion object {
        val STRING_ITEM = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
