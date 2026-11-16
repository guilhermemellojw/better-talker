package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.normalizeText

/**
 * Parser/validador da revisão qualitativa do DeepSeek.
 *
 * Puro/testável. Rejeita JSON malformado, campos obrigatórios ausentes,
 * evidência inventada, parágrafos de substituição e referências de
 * publicação fora do contexto permitido. Limita 2 pontos fortes e 3 melhorias.
 */
object PointReviewQualityParser {

    private val GENERIC = listOf(
        "melhore a clareza",
        "acrescente uma aplicacao",
        "torne mais claro",
        "melhore a transicao",
        "seja mais natural",
        "desenvolva melhor",
        "revise o texto",
    )

    private val REPLACEMENT = listOf(
        "substitua o texto",
        "reescreva o texto",
        "use este texto",
        "troque o texto",
        "no lugar do texto",
    )

    fun parse(
        raw: String,
        evidenceText: String,
        allowedPublicationKeys: Set<String>,
    ): PointQualityReview? {
        if (raw.isBlank()) return null
        val root = rootObject(stripFence(raw)) ?: return null
        val positiveSpan = arraySpan(root, "positives") ?: return null
        val improvementSpan = arraySpan(root, "improvements") ?: return null
        val positiveObjects = objectSpans(positiveSpan)
        val improvementObjects = objectSpans(improvementSpan)
        // Array com conteúdo que não é objeto = saída inválida (não vazia).
        if (positiveSpan.isNotBlank() && positiveObjects.isEmpty()) return null
        if (improvementSpan.isNotBlank() && improvementObjects.isEmpty()) return null

        val positives = positiveObjects.mapNotNull { obj ->
            val observation = stringField(obj, "observation")?.trim().orEmpty()
            if (observation.isBlank()) return@mapNotNull null
            if (isReplacement(observation)) return@mapNotNull null
            if (hasInventedPublication(observation, allowedPublicationKeys)) {
                return@mapNotNull null
            }
            val evidence = stringField(obj, "evidence")
                ?.takeIf { it.isNotBlank() && evidenceMatches(it, evidenceText) }
            PointQualityPositive(observation, evidence)
        }.take(2)

        val improvements = improvementObjects.mapNotNull { obj ->
            val issue = stringField(obj, "issue")?.trim().orEmpty()
            val why = stringField(obj, "why")?.trim().orEmpty()
            if (issue.isBlank() || why.isBlank()) return@mapNotNull null
            val rawCategory = stringField(obj, "category")?.trim().orEmpty()
            if (rawCategory.isBlank()) return@mapNotNull null
            val category = pointQualityCategory(rawCategory)
            val evidence = stringField(obj, "evidence")
                ?.takeIf { it.isNotBlank() && evidenceMatches(it, evidenceText) }
            if (isReplacement(issue) || isReplacement(why)) return@mapNotNull null
            if (isGeneric(issue, evidence)) return@mapNotNull null
            if (hasInventedPublication(issue, allowedPublicationKeys) ||
                hasInventedPublication(why, allowedPublicationKeys) ||
                hasInventedPublication(evidence.orEmpty(), allowedPublicationKeys)
            ) {
                return@mapNotNull null
            }
            PointQualityImprovement(category, issue, why, evidence)
        }.take(3)

        // JSON com itens brutos, mas todos rejeitados = saída inválida.
        if (positiveObjects.isNotEmpty() && positives.isEmpty()) return null
        if (improvementObjects.isNotEmpty() && improvements.isEmpty()) return null

        return PointQualityReview(
            positives = positives,
            improvements = improvements,
        )
    }

    // ---------- validação semântica ----------

    private fun isReplacement(text: String): Boolean {
        val n = normalizeText(text)
        if (text.length > 700) return true
        return REPLACEMENT.any { n.contains(it) }
    }

    private fun isGeneric(issue: String, evidence: String?): Boolean {
        if (!evidence.isNullOrBlank()) return false
        val n = normalizeText(issue)
        if (n.length < 28) return true
        return GENERIC.any { n.contains(it) }
    }

    private fun hasInventedPublication(text: String, allowed: Set<String>): Boolean {
        if (text.isBlank()) return false
        return RefDetector.detect(text)
            .filter { it.isExplicitPublicationRef() }
            .any { it.editionKey !in allowed }
    }

    /**
     * Só trata como referência de publicação o que foi citado de forma
     * explícita (unidade, página/parágrafo ou código com números). Evita o
     * falso positivo de prosa comum: "A publicação ajuda." faz o detector
     * casar o título "Ajuda" sem que o texto esteja citando a publicação.
     */
    private fun com.bettertalker.app.data.util.RefDetector.DetectedRef
        .isExplicitPublicationRef(): Boolean {
        if (page != null || pageEnd != null || paragraph != null ||
            paragraphEnd != null || !chapter.isNullOrBlank() ||
            !article.isNullOrBlank()
        ) {
            return true
        }
        val lower = raw.lowercase()
        if (lower.any { it.isDigit() }) return true
        return lower.contains("§") || lower.contains("pág") ||
            lower.contains("pag.") || lower.contains("cap.") ||
            lower.contains("capítulo") || lower.contains("parág") ||
            lower.contains("parágrafo") || lower.contains("lição")
    }

    private fun evidenceMatches(evidence: String, text: String): Boolean {
        val parts = evidence.split("...", "…").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        return parts.all { part ->
            val e = normalizeText(part)
            val t = normalizeText(text)
            if (e.length < 8) {
                false
            } else if (t.contains(e)) {
                true
            } else {
                val words = e.split(' ').filter { it.length >= 4 }.distinct()
                if (words.isEmpty()) false
                else {
                    val hits = words.count { t.contains(it) }
                    hits >= maxOf(1, (words.size * 0.7).toInt())
                }
            }
        }
    }

    // ---------- JSON manual (JVM tests sem org.json) ----------

    private fun stripFence(raw: String): String =
        raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

    private fun rootObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = matchClose(text, start, '{', '}') ?: return null
        return text.substring(start, end + 1)
    }

    private fun arraySpan(scope: String, key: String): String? {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[").find(scope) ?: return null
        val open = m.range.last
        val close = matchClose(scope, open, '[', ']') ?: return null
        return scope.substring(open + 1, close)
    }

    private fun objectSpans(span: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < span.length) {
            if (span[i] == '{') {
                val end = matchClose(span, i, '{', '}') ?: break
                out += span.substring(i, end + 1)
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    private fun matchClose(text: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

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
                val next = scope[i + 1]
                when (next) {
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
        return null
    }
}
