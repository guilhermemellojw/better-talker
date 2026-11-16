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

    /**
     * O prompt não inclui o conteúdo integral das publicações. Estas formas
     * explícitas de "já verifiquei/confirmei" são rejeitadas para o card não
     * apresentar uma fonte como comprovada sem consulta ao conteúdo.
     */
    private val UNVERIFIED_SOURCE_CLAIM = listOf(
        Regex(
            """(?<!nao )\b(confirmei|verifiquei|conferi|constatei|comprovei)\s+""" +
                """(o|a|os|as)?\s*(conteudo|ensinamento|paragrafo|artigo|estudo|""" +
                """publicacao|citacao|referencia)\b"""
        ),
        Regex(
            """(?<!nao )\b(esta|foi)\s+(confirmado|verificado|conferido)\b.{0,80}""" +
                """\b(publicacao|paragrafo|artigo|estudo|referencia)\b"""
        ),
    )

    fun parse(
        raw: String,
        evidenceText: String,
        allowedPublicationKeys: Set<String>,
    ): PointQualityReview? {
        if (raw.isBlank()) return null
        val root = rootObject(stripFence(raw)) ?: return null
        val positiveSpan = arraySpanAny(root, "positives", "pontos_fortes", "pontosFortes")
            ?: return null
        val improvementSpan = arraySpanAny(root, "improvements", "melhorias")
            ?: return null
        val positiveObjects = objectSpans(positiveSpan)
        val improvementObjects = objectSpans(improvementSpan)
        // Positivos podem vir como objetos (schema) ou como strings (fallback).
        val positiveStrings = if (positiveObjects.isEmpty()) stringValues(positiveSpan) else emptyList()
        // Array com conteúdo que não é objeto nem string = saída inválida.
        if (positiveSpan.isNotBlank() && positiveObjects.isEmpty() && positiveStrings.isEmpty()) {
            return null
        }
        if (improvementSpan.isNotBlank() && improvementObjects.isEmpty()) return null

        val positives = if (positiveObjects.isNotEmpty()) {
            positiveObjects.mapNotNull { obj ->
                val observation = stringFieldAny(
                    obj, "observation", "observacao", "observação", "ponto_forte", "pontoForte",
                ).orEmpty()
                val evidence = stringFieldAny(obj, "evidence", "evidencia", "evidência")
                    ?.takeIf { it.isNotBlank() && evidenceMatches(it, evidenceText) }
                positiveOrNull(observation, evidence, allowedPublicationKeys)
            }.take(2)
        } else {
            positiveStrings.mapNotNull { observation ->
                positiveOrNull(observation, null, allowedPublicationKeys)
            }.take(2)
        }

        val improvements = improvementObjects.mapNotNull { obj ->
            val issue = stringFieldAny(obj, "issue", "problema")?.trim().orEmpty()
            val why = stringFieldAny(
                obj, "why", "por_que_ajuda", "porQueAjuda", "justificativa",
            )?.trim().orEmpty()
            if (issue.isBlank() || why.isBlank()) return@mapNotNull null
            val rawCategory = stringFieldAny(obj, "category", "categoria")?.trim().orEmpty()
            if (rawCategory.isBlank()) return@mapNotNull null
            val category = pointQualityCategory(rawCategory)
            val evidence = stringFieldAny(obj, "evidence", "evidencia", "evidência")
                ?.takeIf { it.isNotBlank() && evidenceMatches(it, evidenceText) }
            if (isReplacement(issue) || isReplacement(why)) return@mapNotNull null
            if (hasUnverifiedSourceClaim(issue) || hasUnverifiedSourceClaim(why)) {
                return@mapNotNull null
            }
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
        val rawPositiveCount = positiveObjects.size + positiveStrings.size
        if (rawPositiveCount > 0 && positives.isEmpty()) return null
        if (improvementObjects.isNotEmpty() && improvements.isEmpty()) return null

        return PointQualityReview(
            positives = positives,
            improvements = improvements,
        )
    }

    // ---------- validação semântica ----------

    private fun positiveOrNull(
        observation: String,
        evidence: String?,
        allowed: Set<String>,
    ): PointQualityPositive? {
        val trimmed = observation.trim()
        if (trimmed.isBlank()) return null
        if (isReplacement(trimmed)) return null
        if (hasUnverifiedSourceClaim(trimmed)) return null
        if (hasInventedPublication(trimmed, allowed)) return null
        return PointQualityPositive(trimmed, evidence)
    }

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

    private fun hasUnverifiedSourceClaim(text: String): Boolean {
        if (text.isBlank()) return false
        val n = normalizeText(text)
        return UNVERIFIED_SOURCE_CLAIM.any { it.containsMatchIn(n) }
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

    /** Aceita o nome canônico e aliases em português (fallback do modelo). */
    private fun arraySpanAny(scope: String, vararg keys: String): String? {
        keys.forEach { key -> arraySpan(scope, key)?.let { return it } }
        return null
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
        return parseJsonString(scope, i)?.first
    }

    /** Lê uma string JSON a partir de [start] (aspas) e devolve valor + próximo índice. */
    private fun parseJsonString(scope: String, start: Int): Pair<String, Int>? {
        if (start >= scope.length || scope[start] != '"') return null
        var i = start + 1
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
            if (c == '"') return out.toString() to (i + 1)
            out.append(c)
            i++
        }
        return null
    }

    /** Extrai strings de um array simples (positivos no fallback em português). */
    private fun stringValues(span: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < span.length) {
            if (span[i] == '"') {
                val parsed = parseJsonString(span, i) ?: break
                out += parsed.first
                i = parsed.second
            } else {
                i++
            }
        }
        return out
    }

    /** Aceita o nome canônico e aliases em português (fallback do modelo). */
    private fun stringFieldAny(scope: String, vararg keys: String): String? {
        keys.forEach { key -> stringField(scope, key)?.let { return it } }
        return null
    }
}
