package com.bettertalker.app.data.verify

import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.normalizeText

/**
 * Fase 18 — BLOCO B. Verificação de fidelidade F6 como Kotlin puro.
 *
 * Porte conceitual de `claimExtractor.ts` + `verifier.ts` do web. Mesmas
 * regras: suporte documental conservador, dúvida => partial/insufficient,
 * tipos não-factuais nunca passam por retrieval, teto parcial para
 * interpretive, thresholds 0.45/0.5, training nunca como autoridade factual.
 *
 * O retrieval entra como lambda injetada (a ViewModel fornece a backedada
 * pelo Room; os testes usam candidatos fake). Sem IO, sem Android.
 */

const val MAX_CLAIMS_PER_TEXT = 20
const val SUPPORT_LEXICAL_MIN = 0.45
const val SUPPORT_COVERAGE_MIN = 0.5

enum class ClaimType { FACTUAL, INTERPRETIVE, BIBLICAL, TRAINING, CREATIVE, RHETORICAL, APPLICATION }

enum class SupportStatus(val serial: String) {
    SUPPORTED("supported"),
    PARTIALLY_SUPPORTED("partially_supported"),
    INSUFFICIENT("insufficient"),
    CREATIVE("creative")
}

data class ExtractedClaim(
    val id: String,
    val text: String,
    val type: ClaimType,
    val blockId: String? = null,
    val start: Int = 0,
    val end: Int = 0
)

enum class ClaimSupport { SUPPORTS, PARTIAL }

data class ClaimEvidence(
    val claimId: String,
    val evidenceId: String,
    val support: ClaimSupport,
    val score: Double = 0.0,
    val reason: String? = null,
    val snippet: String = "",
    val reference: String = ""
)

data class VerifiedClaim(
    val claim: ExtractedClaim,
    val status: SupportStatus,
    val evidence: List<ClaimEvidence> = emptyList(),
    val reason: String = ""
)

data class VerificationSummary(
    val supported: Int,
    val partial: Int,
    val insufficient: Int,
    val creative: Int
)

data class TextVerification(
    val claims: List<VerifiedClaim>,
    val summary: VerificationSummary,
    /** true = só avaliador local (sem juiz remoto). */
    val limited: Boolean = true,
    val key: String = ""
)

// ---------- Extração ----------

private val QUESTION_START =
    Regex("^(quem|o que|qual|quais|como|quando|onde|por ?que|será|e se|acaso)\\b", RegexOption.IGNORE_CASE)
private val IMAGINE =
    Regex("^(imagine|considere|suponha|feche os olhos|pense em|visualize)\\b", RegexOption.IGNORE_CASE)
private val APPLICATION =
    Regex("(isso (pode )?nos ajud|podemos aplicar|devemos|que possamos|nos ajuda a|vamos aplicar|ponha em prática|coloque em prática)", RegexOption.IGNORE_CASE)
private val TRAINING =
    Regex("(ilustra|transi|introdução|conclusão|tom de voz|contato visual|oratória|no palco|discurso deve|pausa estrat|module a voz)", RegexOption.IGNORE_CASE)
private val INTERPRETIVE =
    Regex("(isso significa|portanto|logo,|ou seja|isso mostra|podemos concluir|a lição|isso nos ensina)", RegexOption.IGNORE_CASE)
private val NUMBER = Regex("\\d[\\d.,]*\\s?%?")
private val BIBLICAL_KEYWORDS =
    Regex("(versículo|texto bíblico|a bíblia (diz|ensina)|escrituras)", RegexOption.IGNORE_CASE)

private data class Sentence(val text: String, val start: Int)

private fun splitSentencesWithOffsets(src: String): List<Sentence> {
    val out = mutableListOf<Sentence>()
    var start = 0
    fun pushUpTo(end: Int) {
        val raw = src.substring(start, end)
        val t = raw.trim()
        if (t.length > 3) out += Sentence(t, start + (raw.length - raw.trimStart().length))
        start = end
    }
    var i = 0
    while (i < src.length) {
        val ch = src[i]
        if (ch == '.' || ch == '!' || ch == '?' || ch == '…') {
            var j = i
            while (j + 1 < src.length && src[j + 1] in ".!?…") j++
            var k = j + 1
            while (k < src.length && (src[k] == '"' || src[k] == '“' || src[k] == '”' || src[k] == ')')) k++
            val prev = if (i - 1 >= 0) src[i - 1] else ' '
            val next = if (k < src.length) src[k] else ' '
            // Nunca partir "24.01", "§3" etc.: ponto entre dígitos.
            val betweenDigits = prev.isDigit() && next.isDigit()
            val rest = src.substring(k)
            val boundary = k >= src.length ||
                Regex("^\\s*[\"“(]*[A-ZÀ-Ú]").containsMatchIn(rest)
            if (!betweenDigits && boundary) {
                pushUpTo(k)
                i = k
                continue
            }
            i = j + 1
            continue
        }
        i++
    }
    pushUpTo(src.length)
    return out
}

/** Divide compostas (§8 web): ';' e 'e/mas' + nova oração. Puro/testável. */
data class CompoundPart(val text: String, val start: Int)

fun splitCompound(sentence: String, base: Int): List<CompoundPart> {
    val delim = Regex(";\\s+|\\s+e\\s+(?=[A-ZÀ-Ú])|\\s+mas\\s+(?=[A-ZÀ-Ú])")
    val parts = mutableListOf<CompoundPart>()
    var cursor = base
    var current = StringBuilder()
    var currentStart = base
    var first = true
    for (m in delim.findAll(sentence)) {
        val chunk = sentence.substring(cursor - base, m.range.first)
        if (first) {
            currentStart = cursor
            first = false
        }
        current.append(chunk)
        if (current.toString().isNotBlank()) {
            parts += CompoundPart(current.toString().trim(), currentStart)
        }
        current = StringBuilder()
        first = true
        cursor = base + m.range.last + 1
    }
    val tail = sentence.substring(cursor - base)
    if (tail.isNotBlank()) {
        parts += CompoundPart(tail.trim(), cursor)
    } else if (parts.isEmpty()) {
        return listOf(CompoundPart(sentence, base))
    }
    return parts.ifEmpty { listOf(CompoundPart(sentence, base)) }
}

private fun classifyType(sentence: String): ClaimType {
    val t = sentence.trim()
    if (t.endsWith("?") || QUESTION_START.containsMatchIn(t)) return ClaimType.RHETORICAL
    if (IMAGINE.containsMatchIn(t)) return ClaimType.CREATIVE
    if (APPLICATION.containsMatchIn(t)) return ClaimType.APPLICATION
    if (TRAINING.containsMatchIn(t)) return ClaimType.TRAINING
    if (RefDetector.detectBible(t).isNotEmpty() || BIBLICAL_KEYWORDS.containsMatchIn(t)) {
        return ClaimType.BIBLICAL
    }
    if (INTERPRETIVE.containsMatchIn(t)) return ClaimType.INTERPRETIVE
    return ClaimType.FACTUAL
}

/** Números que exigem presença literal na evidência. Puro/testável. */
fun extractNumbers(text: String): List<String> {
    return Regex("\\d[\\d.,]*\\s?%?|\\b(19|20)\\d{2}\\b").findAll(text)
        .map { it.value.replace(Regex("\\s+"), "").lowercase() }
        .toSet().toList()
}

fun extractClaims(text: String, blockId: String? = null): List<ExtractedClaim> {
    val claims = mutableListOf<ExtractedClaim>()
    var counter = 0
    fun push(t: String, start: Int, type: ClaimType) {
        if (claims.size >= MAX_CLAIMS_PER_TEXT) return
        counter++
        claims += ExtractedClaim("claim-$counter", t, type, blockId, start, start + t.length)
    }
    for (s in splitSentencesWithOffsets(text)) {
        for (part in splitCompound(s.text, s.start)) {
            if (part.text.length > 3) push(part.text, part.start, classifyType(part.text))
        }
    }
    return claims
}

// ---------- Avaliação ----------

private fun uniqueTokens(normalized: String): List<String> =
    normalized.split(" ").filter { it.isNotBlank() }.distinct()

private fun claimTokens(claim: ExtractedClaim): List<String> =
    uniqueTokens(normalizeText(claim.text))

private fun citationRaws(text: String): List<String> {
    val out = mutableListOf<String>()
    out += RefDetector.detectBible(text).map { "${it.label} ${it.chapter}:${it.verse}" }
    out += RefDetector.detect(text).map { it.raw }
    return out
}

private fun numbersOk(claimText: String, hits: List<RetrievalCandidate>): Pair<Boolean, List<String>> {
    // Dígitos dentro de referências (ex: "w24.01") não são dados a conferir.
    var scrubbed = claimText
    for (raw in citationRaws(claimText)) {
        if (raw.isNotBlank()) scrubbed = scrubbed.split(raw).joinToString(" ")
    }
    val numbers = extractNumbers(scrubbed)
    if (numbers.isEmpty()) return true to emptyList()
    val missing = numbers.filter { n ->
        val needle = n.replace("%", "")
        hits.none { h ->
            val hay = "${h.passage.normalizedText} ${normalizeText(h.passage.ref)}"
            hay.contains(needle)
        }
    }
    return (missing.isEmpty()) to missing
}

private fun refsOk(claimText: String, hits: List<RetrievalCandidate>): Pair<Boolean, String?> {
    val refs = citationRaws(claimText).filter { it.isNotBlank() }
    if (refs.isEmpty()) return true to null
    for (raw in refs) {
        val tokens = uniqueTokens(normalizeText(raw)).filter { it.length >= 2 }
        val found = tokens.isNotEmpty() && hits.any { h ->
            val hay = normalizeText(
                "${h.passage.ref} ${h.passage.symbol ?: ""} ${h.publicationTitle ?: ""} ${h.passage.section}")
            tokens.any { hay.contains(it) }
        }
        if (!found) {
            return false to "Referência \"$raw\" reconhecida, mas sem correspondência nas fontes autorizadas."
        }
    }
    return true to null
}

private fun toEvidence(
    claimId: String,
    hit: RetrievalCandidate,
    support: ClaimSupport,
    reason: String? = null
): ClaimEvidence {
    val p = hit.passage
    val ref = p.ref.ifBlank { hit.publicationTitle ?: p.pubId }
    return ClaimEvidence(
        claimId = claimId,
        evidenceId = p.id,
        support = support,
        score = (hit.finalScore * 100).toInt() / 100.0,
        reason = reason,
        snippet = p.text.take(600),
        reference = ref
    )
}

private fun assessClaim(claim: ExtractedClaim, hits: List<RetrievalCandidate>): VerifiedClaim {
    if (hits.isEmpty()) {
        return VerifiedClaim(claim, SupportStatus.INSUFFICIENT, emptyList(),
            "Nenhuma evidência encontrada nas fontes autorizadas para esta afirmação.")
    }
    val best = hits[0]
    val tokens = claimTokens(claim)
    val coverage = if (tokens.isNotEmpty()) best.matchedTerms.size.toDouble() / tokens.size else 0.0
    val (numbersOk, missing) = numbersOk(claim.text, hits.take(3))
    val (refsOk, refsReason) = refsOk(claim.text, hits.take(3))
    if (!refsOk) {
        return VerifiedClaim(claim, SupportStatus.INSUFFICIENT, emptyList(),
            refsReason ?: "Referência sem suporte.")
    }
    if (!numbersOk) {
        return VerifiedClaim(claim, SupportStatus.PARTIALLY_SUPPORTED,
            hits.take(2).map { toEvidence(claim.id, it, ClaimSupport.PARTIAL,
                "Evidência relacionada, mas sem o dado específico.") },
            "A fonte sustenta parte da afirmação, mas não confirma: ${missing.joinToString(", ")}.")
    }
    val strong = best.lexicalScore >= SUPPORT_LEXICAL_MIN && coverage >= SUPPORT_COVERAGE_MIN
    if (strong) {
        val by = best.passage.ref.ifBlank { best.publicationTitle ?: best.passage.pubId }
        return VerifiedClaim(claim, SupportStatus.SUPPORTED,
            hits.take(2).map { toEvidence(claim.id, it, ClaimSupport.SUPPORTS) },
            "Sustentado por: $by.")
    }
    return VerifiedClaim(claim, SupportStatus.PARTIALLY_SUPPORTED,
        hits.take(2).map { toEvidence(claim.id, it, ClaimSupport.PARTIAL,
            "Correspondência parcial de termos.") },
        "Há correspondência parcial, mas insuficiente para suporte integral.")
}

private fun creativeNote(claim: ExtractedClaim, kind: String): VerifiedClaim =
    VerifiedClaim(claim, SupportStatus.CREATIVE, emptyList(),
        "$kind — não exige comprovação factual.")

/**
 * Verifica um texto. [retrieve] recebe (query, training?) e devolve
 * candidatos já ranqueados (a ViewModel fornece Room; testes usam fake).
 * Puro/testável (suspend só pela assinatura do retrieval).
 */
suspend fun verifyText(
    text: String,
    blockId: String? = null,
    scopeKey: String = "",
    limitPerClaim: Int = 3,
    retrieve: suspend (query: String, training: Boolean) -> List<RetrievalCandidate>
): TextVerification {
    val key = hashText("${blockId ?: ""}|$text|$scopeKey")
    val assessed = mutableListOf<VerifiedClaim>()
    for (claim in extractClaims(text, blockId)) {
        val vc = when (claim.type) {
            ClaimType.CREATIVE -> creativeNote(claim, "Construção criativa/retórica")
            ClaimType.RHETORICAL -> creativeNote(claim, "Pergunta retórica")
            ClaimType.APPLICATION -> creativeNote(claim, "Aplicação pessoal")
            ClaimType.TRAINING -> {
                val hits = retrieve(claim.text, true).take(limitPerClaim.coerceAtLeast(0))
                if (hits.isEmpty()) {
                    VerifiedClaim(claim, SupportStatus.INSUFFICIENT, emptyList(),
                        "Sem orientação correspondente em BE/TH.")
                } else {
                    val best = hits[0]
                    val strong = best.lexicalScore >= SUPPORT_LEXICAL_MIN
                    val by = best.passage.ref.ifBlank {
                        best.publicationTitle ?: best.passage.pubId
                    }
                    VerifiedClaim(claim, if (strong) SupportStatus.SUPPORTED
                    else SupportStatus.PARTIALLY_SUPPORTED,
                        hits.take(2).map {
                            toEvidence(claim.id, it,
                                if (strong) ClaimSupport.SUPPORTS else ClaimSupport.PARTIAL,
                                if (strong) null else "Correspondência parcial em BE/TH.")
                        },
                        if (strong) "Orientação encontrada em: $by."
                        else "Orientação parcialmente correspondente em BE/TH.")
                }
            }
            else -> {
                val hits = retrieve(claim.text, false).take(limitPerClaim.coerceAtLeast(0))
                var a = assessClaim(claim, hits)
                // Interpretive é derivada — teto parcial, nunca supported direto.
                if (claim.type == ClaimType.INTERPRETIVE && a.status == SupportStatus.SUPPORTED) {
                    a = a.copy(
                        status = SupportStatus.PARTIALLY_SUPPORTED,
                        evidence = a.evidence.map { it.copy(support = ClaimSupport.PARTIAL) },
                        reason = a.reason + " (Interpretação: teto parcial por regra conservadora.)")
                }
                a
            }
        }
        assessed += vc
    }
    return TextVerification(
        claims = assessed,
        summary = VerificationSummary(
            supported = assessed.count { it.status == SupportStatus.SUPPORTED },
            partial = assessed.count { it.status == SupportStatus.PARTIALLY_SUPPORTED },
            insufficient = assessed.count { it.status == SupportStatus.INSUFFICIENT },
            creative = assessed.count { it.status == SupportStatus.CREATIVE }),
        limited = true,
        key = key
    )
}
