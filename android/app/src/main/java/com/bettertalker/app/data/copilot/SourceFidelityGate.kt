package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.RefCanonicalizer
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.normalizeText

/**
 * BETA-TRUST/P1 — gate de fidelidade às fontes antes do rascunho/histórico/
 * conteúdo copiável. Puro: sem Android, Room, rede ou LLM.
 *
 * Distingue:
 * - [FidelityFinding.LITERAL_SUPPORTED] — citação literal conferida com o
 *   texto recuperado (TNM/publicação) presente no corpus;
 * - [FidelityFinding.PARAPHRASE_ALLOWED] — paráfrase explicitamente marcada,
 *   sem aspas e sem atribuição indevida;
 * - [FidelityFinding.REFERENCE_SUPPORTED]/[FidelityFinding.REFERENCE_FROM_QUESTION]
 *   — referência com texto local ou mencionada na pergunta do usuário;
 * - [FidelityFinding.LITERAL_UNSUPPORTED]/[FidelityFinding.REFERENCE_UNSUPPORTED]
 *   — afirmação bíblica sem suporte verificável: removida do texto devolvido;
 * - [FidelityFinding.TERM_QUOTE] — aspas de termo/ilustração, não citação
 *   bíblica (correção dos falsos positivos AI-06).
 */
enum class FidelityFinding {
    LITERAL_SUPPORTED,
    LITERAL_UNSUPPORTED,
    REFERENCE_SUPPORTED,
    REFERENCE_UNSUPPORTED,
    REFERENCE_FROM_QUESTION,
    PUBLICATION_UNSUPPORTED,
    PARAPHRASE_ALLOWED,
    TERM_QUOTE,
}

data class SourceFidelityDecision(
    val text: String,
    val removed: List<String>,
    val findings: List<FidelityFinding>,
    val notice: String?,
) {
    val blocked: Boolean
        get() = findings.any {
            it == FidelityFinding.LITERAL_UNSUPPORTED ||
                it == FidelityFinding.REFERENCE_UNSUPPORTED ||
                it == FidelityFinding.PUBLICATION_UNSUPPORTED
        }
}

object SourceFidelityGate {

    private const val MIN_CLAIM_QUOTE = 20
    private const val LONG_CLAIM_QUOTE = 80

    private val QUOTE = Regex("\"([^\"]{8,})\"|“([^”]{8,})”", RegexOption.DOT_MATCHES_ALL)
    private val VERSE = Regex(
        """\b((?:[1-3]\s*)?[A-Za-zÀ-ÿ]+\.?)\s+(\d{1,3})\s*:\s*(\d{1,3})""",
    )
    private val CLAIM_CONTEXT = Regex(
        "(?i)(b[íi]blia|deus|jesus|cristo|escrito|disse|ensina|promete|" +
            "tnm|tradu[çc][aã]o do novo mundo|vers[íi]culo|salmo|ap[óo]stolo|profeta)",
    )
    private val PARAPHRASE = Regex(
        "(?i)\\b(par[áa]frase|em outras palavras|sugest[aã]o|ilustra[çc][aã]o)\\b",
    )

    private fun body(match: MatchResult): String =
        match.groupValues[1].ifBlank { match.groupValues[2] }

    /** Aspas só são afirmação bíblica quando o contexto as apresenta como tal. */
    private fun isClaimContext(text: String, start: Int, end: Int): Boolean {
        val before = text.substring((start - 180).coerceAtLeast(0), start)
        val after = text.substring(end.coerceAtMost(text.length), (end + 90).coerceAtMost(text.length))
        return CLAIM_CONTEXT.containsMatchIn(before) || CLAIM_CONTEXT.containsMatchIn(after) ||
            VERSE.containsMatchIn(before) || VERSE.containsMatchIn(after)
    }

    /**
     * Aplica o gate. [questionRefs] aceita refs canônicas da mensagem do
     * usuário ("jo1717"): elas explicam a referência, mas **nunca** autorizam
     * citação literal sem o texto recuperado no corpus.
     */
    fun enforce(
        text: String,
        corpus: List<String>,
        questionRefs: List<String> = emptyList(),
    ): SourceFidelityDecision {
        if (text.isBlank()) {
            return SourceFidelityDecision(text, emptyList(), emptyList(), null)
        }
        val corpusFlat = corpus.joinToString(" ") { normalizeText(it) }
        val canonCorpus = corpus.flatMap { RefCanonicalizer.canonicalize(it) }.toSet()
        val questionKeys = questionRefs
            .flatMap { RefCanonicalizer.canonicalize(it) }
            .toSet() + questionRefs.map { RefCanonicalizer.canonicalBook(it) }.toSet()

        var out = text
        val removed = mutableListOf<String>()
        val findings = mutableListOf<FidelityFinding>()

        // 1) Citações literais: somente o que está no texto recuperado.
        QUOTE.findAll(text).toList().forEach { match ->
            val quote = body(match)
            val norm = normalizeText(quote)
            if (norm.length < MIN_CLAIM_QUOTE) {
                findings += FidelityFinding.TERM_QUOTE
                return@forEach
            }
            if (corpusFlat.contains(norm)) {
                findings += FidelityFinding.LITERAL_SUPPORTED
                return@forEach
            }
            val claim = norm.length >= LONG_CLAIM_QUOTE ||
                isClaimContext(text, match.range.first, match.range.last + 1)
            if (claim) {
                findings += FidelityFinding.LITERAL_UNSUPPORTED
                if (out.contains(match.value)) {
                    out = out.replace(match.value, "")
                    removed += quote
                }
            } else {
                findings += FidelityFinding.TERM_QUOTE
            }
        }

        // 2) Referências bíblicas: comparam livro+capítulo+versículo — não só
        // o nome do livro (falha que deixava passar Jó 38:90 com "Jó" no corpus).
        VERSE.findAll(text).toList().forEach { match ->
            val book = match.groupValues[1].trim()
            val chapter = match.groupValues[2].toIntOrNull() ?: return@forEach
            val firstVerse = match.groupValues[3].toIntOrNull() ?: return@forEach
            val tail = Regex("""\d{1,3}\s*[-–,]\s*(\d{1,3})""")
                .find(match.value.substringAfter(":"))
            val lastVerse = tail?.groupValues?.get(1)?.toIntOrNull() ?: firstVerse
            if (lastVerse < firstVerse || lastVerse - firstVerse > 50) {
                findings += FidelityFinding.REFERENCE_UNSUPPORTED
                out = out.replace(match.value, "")
                removed += match.value
                return@forEach
            }
            val keys = (firstVerse..lastVerse).map { v ->
                RefCanonicalizer.canonicalBook("$book $chapter:$v")
            }
            when {
                keys.all { key -> canonCorpus.any { it.contains(key) } } ->
                    findings += FidelityFinding.REFERENCE_SUPPORTED
                keys.any { key -> key in questionKeys } ->
                    findings += FidelityFinding.REFERENCE_FROM_QUESTION
                else -> {
                    findings += FidelityFinding.REFERENCE_UNSUPPORTED
                    val mention = Regex(
                        """\b${Regex.escape(book)}\s*${chapter}\s*:\s*${firstVerse}(?:\s*[-–,]\s*\d{1,3})?""",
                        RegexOption.IGNORE_CASE,
                    )
                    val hit = mention.find(out)?.value
                    if (hit != null) {
                        out = out.replace(hit, "")
                        removed += hit
                    }
                }
            }
        }

        // 3) Publicações citadas sem trecho indexado não são fonte.
        RefDetector.detect(text).forEach { ref ->
            if (ref.pubKey.equals("be", true) || ref.pubKey.equals("th", true)) return@forEach
            val labelKey = normalizeText(ref.label)
            val editionKey = normalizeText(ref.editionKey)
            val known = corpusFlat.contains(labelKey) || canonCorpus.any { it.contains(editionKey) }
            if (!known && ref.label.isNotBlank()) {
                findings += FidelityFinding.PUBLICATION_UNSUPPORTED
                if (out.contains(ref.label)) {
                    out = out.replace(ref.label, "")
                    removed += ref.label
                }
            }
        }

        if (!blocked(findings) && PARAPHRASE.containsMatchIn(text)) {
            findings += FidelityFinding.PARAPHRASE_ALLOWED
        }
        val notice = if (blocked(findings)) {
            "⚠️ Removi afirmações sem fonte local verificável; use apenas texto recuperado."
        } else {
            null
        }
        return SourceFidelityDecision(
            text = out.replace(Regex("""[ \t]{2,}"""), " ").trim(),
            removed = removed.distinct(),
            findings = findings.distinct(),
            notice = notice,
        )
    }

    /** Citações literais sem apoio no material recuperado (para o dossiê). */
    fun unsupportedLiteralQuotes(text: String, corpus: List<String>): List<String> {
        val corpusFlat = corpus.joinToString(" ") { normalizeText(it) }
        return QUOTE.findAll(text).toList().mapNotNull { match ->
            val quote = body(match)
            val norm = normalizeText(quote)
            if (norm.length < MIN_CLAIM_QUOTE || corpusFlat.contains(norm)) null
            else if (norm.length >= LONG_CLAIM_QUOTE ||
                isClaimContext(text, match.range.first, match.range.last + 1)
            ) quote else null
        }.distinct()
    }

    /** Usado no espelho de streaming e no turno cancelado: nada não verificado
     * pode aparecer como parcial copiável ou persistido no histórico. */
    fun sanitizePartialForDisplay(
        partial: String,
        corpus: List<String>,
        questionRefs: List<String> = emptyList(),
    ): String = enforce(partial, corpus, questionRefs).text

    private fun blocked(findings: List<FidelityFinding>): Boolean = findings.any {
        it == FidelityFinding.LITERAL_UNSUPPORTED ||
            it == FidelityFinding.REFERENCE_UNSUPPORTED ||
            it == FidelityFinding.PUBLICATION_UNSUPPORTED
    }
}
