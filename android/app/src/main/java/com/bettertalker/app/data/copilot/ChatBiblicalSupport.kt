package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.llm.GroundednessVerifier
import com.bettertalker.app.data.util.RefCanonicalizer
import com.bettertalker.app.data.util.RefDetector

/**
 * Fail-safe bíblico para a resposta de chat (P0) — contrato determinístico e testável.
 *
 * Detecta, na resposta gerada pelo modelo, citações bíblicas (livros + versículos)
 * que não têm suporte em EVIDÊNCIA DOCUMENTAL elegível (corpus injectado). Texto do
 * usuário, S-34, BE/TH e memórias do Mentor não são fonte documental — é falta de
 * retorno, não desconfiança.
 *
 * Não impõe bloqueio: gera o notice visível (⚠️) que informa que a citação não
 * consta do acervo local. A resposta segue após informação correta, como desejado.
 *
 * Puro/testável: sem Android, sem IO, sem rede, sem LLM.
 */
object ChatBiblicalSupport {

    /** Citação bíblica detectada na resposta, sem suporte documental no corpus. */
    data class BiblicalGap(val label: String, val chapter: Int, val verse: Int) {
        val ref: String get() = "$label $chapter:$verse"
    }

    /**
     * Detecta, na resposta, versículos bíblicos citados mas não presentes no
     * corpus documental (canonicalizado — espelho do GroundednessVerifier).
     *
     * Sem correspondência no corpus, nem hash relevante/ranking — a verdade é
     * o corpus. A resposta pode conter citações a partir da base do modelo.
     */
    fun biblicalGaps(
        answer: String,
        documentaryCorpus: List<String>,
    ): List<BiblicalGap> {
        if (answer.isBlank()) return emptyList()
        val canonCorpus = documentaryCorpus
            .map(GroundednessVerifier::normalizeForComparison)
            .flatMap(RefCanonicalizer::canonicalize)
            .toSet()
        return RefDetector.detectBible(answer)
            .map { ref ->
                val form = RefCanonicalizer.canonicalBook("${ref.label} ${ref.chapter}:${ref.verse}")
                ref to canonCorpus.none { it.contains(form) }
            }
            .filter { it.second }
            .map { BiblicalGap(label = it.first.label, chapter = it.first.chapter, verse = it.first.verse) }
            .distinctBy { Triple(it.label, it.chapter, it.verse) }
    }

    /**
     * TRUST-P0A-2 (S1/S2/S4) — citações literais sem apoio no corpus
     * documental. Texto do usuário, memória, S-34 e paráfrases NÃO contam
     * como evidência — apenas o corpus recebido é examinado.
     */
    fun unsupportedQuotes(
        answer: String,
        documentaryCorpus: List<String>,
    ): List<String> = GroundednessVerifier.unsupportedQuotes(answer, documentaryCorpus)

    /** Rótulo de fonte restrito ao trecho efetivamente recuperado. */
    fun retrievedExcerpt(sourceLabel: String, excerpt: String): String? =
        if (sourceLabel.isBlank() || excerpt.isBlank()) null
        else "$sourceLabel — trecho recuperado: “$excerpt”"

    /**
     * Citações que aparecem em OUTRA fonte, mas não no documento que contém a
     * referência atribuída pela resposta: presença em outra fonte não prova a
     * atribuição. Comparação por documento — nunca por corpus concatenado.
     */
    /** Identidades de referência EXTRAÍDAS do documento — não o texto inteiro canonicalizado. */
    private fun refFormsInSource(source: String): Set<String> =
        RefDetector.detectBible(source)
            .map { RefCanonicalizer.canonicalBook("${it.label} ${it.chapter}:${it.verse}") }
            .toSet()

    /**
     * F1.1/F1.2 — ocorrências com a posição original preservada: a mesma frase
     * citada duas vezes pode ter atribuições distintas; role-play e afirmação
     * factual são tratadas por ocorrência, nunca por texto deduplicado.
     */
    fun misattributedQuoteOccurrences(
        answer: String,
        documentaryCorpus: List<String>,
    ): List<GroundednessVerifier.QuoteOccurrence> {
        val occurrences = GroundednessVerifier.factualQuoteOccurrences(answer)
        val refs = RefDetector.detectBible(answer)
        if (occurrences.isEmpty() || refs.isEmpty()) return emptyList()
        return occurrences.filter { occ ->
            val flat = GroundednessVerifier.normalizeForComparison(occ.text)
            val withQuote = documentaryCorpus.filter { source ->
                GroundednessVerifier.normalizeForComparison(source).contains(flat)
            }
            if (withQuote.isEmpty()) return@filter false // ausência: unsupportedQuotes

            // Vínculo por proximidade: ref mais próxima ANTES desta ocorrência.
            val prefix = answer.substring(0, occ.start.coerceAtMost(answer.length))
            val linked = refs.mapNotNull { ref ->
                val forms = listOf(
                    "${ref.label} ${ref.chapter}:${ref.verse}",
                    "${ref.bookNorm} ${ref.chapter}:${ref.verse}",
                )
                val refAt = forms.mapNotNull { form ->
                    prefix.lastIndexOf(form).takeIf { it >= 0 }
                }.maxOrNull()
                refAt?.takeIf { occ.start - it <= 160 }?.let { ref to it }
            }.maxByOrNull { it.second }?.first
                ?: return@filter true // vínculo ambíguo: não certificar

            val wanted = RefCanonicalizer.canonicalBook(
                "${linked.label} ${linked.chapter}:${linked.verse}",
            )
            wanted !in withQuote.flatMap(::refFormsInSource).toSet()
        }
    }

    /** API legada por texto — mantém P2/P3. */
    fun misattributedQuotes(
        answer: String,
        documentaryCorpus: List<String>,
    ): List<String> = misattributedQuoteOccurrences(answer, documentaryCorpus)
        .map { it.text }.distinct()

    /** Aviso determinístico de suporte ausente — refs e citações literais. */
    fun supportNotice(
        answer: String,
        documentaryCorpus: List<String>,
        includeUnsupportedQuotes: Boolean = true,
        includeMisattributed: Boolean = true,
        /** F2.1 — deduplicação TIPADA: citações já cobertas pelo aviso anterior. */
        existingUnsupportedQuotes: Set<String> = emptySet(),
    ): String? {
        val gaps = biblicalGaps(answer, documentaryCorpus)
        val quotes = if (includeUnsupportedQuotes) {
            unsupportedQuotes(answer, documentaryCorpus)
                .filterNot { it in existingUnsupportedQuotes }
        } else emptyList()
        // F2: atribuição incorreta é achado DISTINTO de citação sem apoio —
        // nunca suprimida só porque já existe aviso de citação no turno.
        val misattributed = if (includeMisattributed) misattributedQuotes(answer, documentaryCorpus) else emptyList()
        return buildList {
            notice(gaps)?.let(::add)
            if (quotes.isNotEmpty()) {
                add(
                    "⚠️ Trecho(s) citado(s) sem retorno do acervo local: " +
                        quotes.joinToString(", ") { "“$it”" } +
                        ". Não os apresente como verificado.",
                )
            }
            if (misattributed.isNotEmpty()) {
                add(
                    "⚠️ Citação(ões) atribuída(s) a referência sem fonte correspondente: " +
                        misattributed.joinToString(", ") { "“$it”" } + ".",
                )
            }
        }.joinToString("\n\n").ifBlank { null }
    }

    /** Notice visível — mesma família dos avisos do chat. Puro/testável. */
    fun notice(gaps: List<BiblicalGap>): String? =
        gaps.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.ref }
            ?.let { refs ->
                "⚠️ Citação(ões) bíblicas sem retorno do acervo local: $refs. " +
                    "Use paráfrase, ou informe que consultei apenas o acervo local."
            }

    /**
     * Monta o notice final do chat = aviso existente + notice bíblico
     * (semelhante: noticeInparkStyle). Puro/testável.
     */
    fun noticeWithBiblicalSafety(
        existingNotice: String?,
        answer: String,
        corpus: List<String>,
        /** F2.1 — quando o chamador conhece as citações já sinalizadas. */
        existingUnsupportedQuotes: Set<String> = emptySet(),
    ): String? {
        // P5 — sem supressão global por texto do aviso: somente o conjunto
        // tipado `existingUnsupportedQuotes` identifica achados já cobertos.
        // Controle 10 (#48) preservado: sempre calcular e concatenar avisos de
        // naturezas distintas, com um único separador.
        val biblicalNotice = supportNotice(
            answer = answer,
            documentaryCorpus = corpus,
            includeUnsupportedQuotes = true,
            includeMisattributed = true,
            existingUnsupportedQuotes = existingUnsupportedQuotes,
        )
        return listOfNotNull(existingNotice, biblicalNotice).joinToString("\n\n").ifBlank { null }
    }
}
