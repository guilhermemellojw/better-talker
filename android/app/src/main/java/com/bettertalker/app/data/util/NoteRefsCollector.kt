package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

/**
 * Entrada mínima de uma seção para o coletor de refs. Mantém o coletor puro:
 * o repositório mapeia a entidade/domínio para estes campos.
 */
data class SectionRefs(
    val title: String = "",
    val contentHtml: String = "",
    val bibleRefs: List<String> = emptyList(),
    val publicationRefs: List<PublicationRef> = emptyList(),
)

/** Entrada mínima de um subponto para o coletor de refs. */
data class SubPointRefs(
    val outlineText: String = "",
    val developedHtml: String = "",
    val bibleRefs: List<String> = emptyList(),
    val publicationRefs: List<PublicationRef> = emptyList(),
)

/**
 * Refs únicas da nota, separadas por trilho. A mesma lista alimenta o
 * checklist de fontes e o Copilot (citedSources/refScope).
 */
data class NoteRefs(
    val publications: List<RefDetector.DetectedRef>,
    val bibles: List<RefDetector.BibleRef>,
) {
    val isEmpty: Boolean get() = publications.isEmpty() && bibles.isEmpty()

    companion object {
        val EMPTY = NoteRefs(emptyList(), emptyList())
    }
}

fun SpeechSection.toSectionRefs(): SectionRefs = SectionRefs(
    title = title,
    contentHtml = contentHtml,
    bibleRefs = bibleRefs,
    publicationRefs = publicationRefs,
)

fun SubPoint.toSubPointRefs(): SubPointRefs = SubPointRefs(
    outlineText = outlineText,
    developedHtml = developedHtml,
    bibleRefs = bibleRefs,
    publicationRefs = publicationRefs,
)

/**
 * Coletor ÚNICO de referências da nota (puro/testável).
 *
 * Fontes: título da nota, mdText/plainText, refs do esboço, título/conteúdo
 * das seções, refs explícitas das seções e subpontos, texto desenvolvido dos
 * subpontos. HTML é convertido em texto antes da detecção.
 *
 * Dedup canônico:
 * - publicação: edição + unidade + página/§ (o mesmo ponto pode chegar como
 *   "§ 4" ou "ponto 4");
 * - Bíblia: livro normalizado + capítulo + versículo.
 */
object NoteRefsCollector {

    fun collect(
        noteTitle: String = "",
        mdText: String = "",
        plainText: String = "",
        outlineRefsJson: String = "",
        sections: List<SectionRefs> = emptyList(),
        subPoints: List<SubPointRefs> = emptyList(),
    ): NoteRefs {
        val publications = mutableListOf<RefDetector.DetectedRef>()
        val bibles = mutableListOf<RefDetector.BibleRef>()

        fun addText(raw: String?) {
            if (raw.isNullOrBlank()) return
            // O editor salva pontuação como entidade HTML (&colon;, &period;,
            // &semi;) em alguns fluxos. Sem decodificar, "Jo&atilde;o
            // 14&colon;28" nunca casa com a ref bíblica.
            val text = decodeHtmlEntities(raw)
            publications += RefDetector.detect(text)
            bibles += RefDetector.detectBible(text)
        }

        addText(noteTitle)
        addText(mdText)
        addText(plainText)
        publications += RefDetector.detectedFromJson(outlineRefsJson)

        sections.forEach { section ->
            addText(section.title)
            addText(htmlToPlainText(section.contentHtml))
            section.publicationRefs.forEach { ref ->
                publicationRefToDetected(ref)?.let { publications += it }
            }
            section.bibleRefs.forEach { bibles += RefDetector.detectBible(it) }
        }

        subPoints.forEach { subPoint ->
            addText(htmlToPlainText(subPoint.outlineText))
            addText(htmlToPlainText(subPoint.developedHtml))
            subPoint.publicationRefs.forEach { ref ->
                publicationRefToDetected(ref)?.let { publications += it }
            }
            subPoint.bibleRefs.forEach { bibles += RefDetector.detectBible(it) }
        }

        return NoteRefs(
            publications = dedupUnits(publications),
            bibles = bibles.distinctBy {
                "${normalizeText(it.label)}|${it.chapter}|${it.verse}"
            },
        )
    }

    /**
     * Dedup por publicação + unidade. Quando o mesmo ponto chega como
     * "lmd § 4" (genérico, sem capítulo) e "lmd lição 7 ponto 4" (específico),
     * a menção genérica não vira uma segunda linha — o grupo da publicação já
     * cobre a menção; a unidade específica é a identidade.
     */
    private fun dedupUnits(refs: List<RefDetector.DetectedRef>): List<RefDetector.DetectedRef> {
        val specific = refs.filter { !it.chapter.isNullOrBlank() || !it.article.isNullOrBlank() }
        val generic = refs.filter { it.chapter.isNullOrBlank() && it.article.isNullOrBlank() }
        val keptGeneric = generic.filterNot { g ->
            specific.any { s ->
                s.pubKey == g.pubKey && (
                    // genérica sem unidade: a específica da mesma publicação cobre
                    (g.page == null && g.paragraph == null && g.paragraphEnd == null) ||
                        // mesmo ponto, mas a específica traz o capítulo/artigo
                        (s.page == g.page && s.paragraph == g.paragraph &&
                            s.paragraphEnd == g.paragraphEnd)
                    )
            }
        }
        return (specific + keptGeneric).distinctBy { sourceKey(it) }
    }

    /**
     * Chave canônica de dedup: edição + unidade (artigo/capítulo) + página/§.
     * Mais estável que o `raw` (o mesmo ponto pode vir como "§ 4" ou
     * "ponto 4" do parser/JSON da seção).
     */
    fun sourceKey(ref: RefDetector.DetectedRef): String = buildString {
        append(ref.editionKey)
        append('|').append(normalizeText(ref.article.orEmpty()))
        append('|').append(normalizeText(ref.chapter.orEmpty()))
        append('|').append(ref.page ?: -1)
        append('|').append(ref.paragraph ?: -1)
        append('|').append(ref.paragraphEnd ?: -1)
    }

    /**
     * Converte o `PublicationRef` persistido (símbolo canônico) de volta para
     * `DetectedRef`, preservando página/§/artigo/capítulo. Retorna null para
     * símbolo desconhecido (nunca inventa fonte).
     */
    fun publicationRefToDetected(ref: PublicationRef): RefDetector.DetectedRef? {
        val symbol = ref.symbol.trim().lowercase()
        magazineSpec(symbol)?.let { spec ->
            return RefDetector.DetectedRef(
                raw = publicationRaw(ref),
                kind = RefDetector.Kind.MAGAZINE,
                pubKey = spec.pubKey,
                editionKey = spec.editionKey,
                label = spec.label,
                article = ref.article,
                chapter = ref.chapter,
                page = ref.page,
                pageEnd = ref.pageEnd,
                paragraph = ref.paragraph,
                paragraphEnd = ref.paragraphEnd,
            )
        }
        val canonical = PubCatalog.resolveSymbol(symbol) ?: return null
        if (!PubCatalog.isSymbol(canonical) && BASE_PUBS.none { it.slot == canonical }) {
            return null
        }
        val chapter = ref.chapter?.trim()?.takeIf { it.isNotBlank() }
        val article = ref.article?.trim()?.takeIf { it.isNotBlank() }
            ?: chapter?.takeIf { it.contains("apêndice", ignoreCase = true) }
        return RefDetector.DetectedRef(
            raw = publicationRaw(ref),
            kind = RefDetector.Kind.BOOK,
            pubKey = canonical,
            editionKey = "book|$canonical",
            label = PubCatalog.titleOf(canonical) ?: canonical,
            article = article,
            chapter = chapter,
            page = ref.page,
            pageEnd = ref.pageEnd,
            paragraph = ref.paragraph,
            paragraphEnd = ref.paragraphEnd,
        )
    }

    private data class MagazineSpec(
        val pubKey: String,
        val editionKey: String,
        val label: String,
    )

    private fun magazineSpec(symbol: String): MagazineSpec? {
        fun year(yy: String): Int = yy.toInt().let { if (it < 100) 2000 + it else it }

        Regex("""^w(\d{2})\.(\d{1,2})$""").find(symbol)?.let { m ->
            val y = year(m.groupValues[1]); val n = m.groupValues[2].toInt()
            return MagazineSpec("w", "w|$y|$n", "A Sentinela $n/$y")
        }
        Regex("""^wp(\d{2})\.(\d{1,2})$""").find(symbol)?.let { m ->
            val y = year(m.groupValues[1]); val n = m.groupValues[2].toInt()
            return MagazineSpec("wp", "wp|$y|$n", "A Sentinela $n/$y")
        }
        Regex("""^g\s+(\d{1,2})/(\d{2,4})$""").find(symbol)?.let { m ->
            val n = m.groupValues[1].toInt(); val y = year(m.groupValues[2])
            return MagazineSpec("g", "g|$y|$n", "Despertai! $n/$y")
        }
        Regex("""^gn\s+(\d{1,2})/(\d{2,4})$""").find(symbol)?.let { m ->
            val n = m.groupValues[1].toInt(); val y = year(m.groupValues[2])
            return MagazineSpec("gn", "gn|$y|$n", "Despertai! $n/$y")
        }
        Regex("""^mwb(\d{2})\.(\d{1,2})$""").find(symbol)?.let { m ->
            val y = year(m.groupValues[1]); val n = m.groupValues[2].toInt()
            return MagazineSpec("mwb", "mwb|$y|$n", "Apostila Vida e Ministério $n/$y")
        }
        // Formato antigo quinzenal: "w94 1/8" / "g93 8/1" (dia/mês).
        Regex("""^(w|g)(\d{2})\s+(\d{1,2})/(\d{1,2})$""").find(symbol)?.let { m ->
            val key = m.groupValues[1]
            val y = year(m.groupValues[2])
            val day = m.groupValues[3].toInt()
            val month = m.groupValues[4].toInt()
            return MagazineSpec(
                key,
                "$key|$y|$month|$day",
                if (key == "w") "A Sentinela $day/$month/$y" else "Despertai! $day/$month/$y",
            )
        }
        return null
    }

    private fun publicationRaw(ref: PublicationRef): String = buildString {
        val article = ref.article?.trim()?.takeIf { it.isNotBlank() }
        val chapter = ref.chapter?.trim()?.takeIf { it.isNotBlank() }
        append(ref.symbol)
        if (article != null && article != chapter) append(" — ").append(article)
        chapter?.let { append(' ').append(it) }
        ref.page?.let { append(" pág. ").append(it) }
        ref.paragraph?.let { paragraph ->
            if (chapter?.contains("apêndice", ignoreCase = true) == true) {
                append(" ponto ").append(paragraph)
            } else {
                append(" § ").append(paragraph)
            }
        }
    }
}
