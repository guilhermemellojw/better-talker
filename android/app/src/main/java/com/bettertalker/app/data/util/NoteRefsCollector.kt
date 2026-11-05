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
        // Página/§ também são unidade: uma revista "w23.08 29-30 §§ 12-13"
        // não pode conviver com a menção genérica "w23.08" da mesma edição.
        fun hasUnit(ref: RefDetector.DetectedRef): Boolean =
            !ref.chapter.isNullOrBlank() || !ref.article.isNullOrBlank() ||
                ref.page != null || ref.pageEnd != null ||
                ref.paragraph != null || ref.paragraphEnd != null
        fun hasChapterOrArticle(ref: RefDetector.DetectedRef): Boolean =
            !ref.chapter.isNullOrBlank() || !ref.article.isNullOrBlank()
        // O artigo pode estar no editionKey ("book|it|a:presuncao") ou no
        // campo article; para comparar edições, use a base comum.
        fun baseEdition(ref: RefDetector.DetectedRef): String =
            ref.editionKey.substringBefore("|a:")
        val withUnit = refs.filter(::hasUnit)
        val noUnit = refs.filterNot(::hasUnit).filterNot { g ->
            // Só a MESMA edição cobre a menção genérica: w23.08 genérico
            // não pode sumir por causa de w25.07 (ambos pubKey "w").
            withUnit.any { s -> baseEdition(s) == baseEdition(g) }
        }
        val withChapter = withUnit.filter(::hasChapterOrArticle)
        val withoutChapter = withUnit.filterNot(::hasChapterOrArticle)
        val keptWithoutChapter = withoutChapter.filterNot { g ->
            // "lmd lição 7 ponto 4" + ref persistida {"lmd", §4}: a versão
            // sem capítulo é a mesma unidade e não vira segunda linha.
            withChapter.any { s ->
                baseEdition(s) == baseEdition(g) &&
                    s.page == g.page && s.paragraph == g.paragraph &&
                    (s.paragraphEnd ?: s.paragraph) ==
                    (g.paragraphEnd ?: g.paragraph)
            }
        }
        return (withChapter + keptWithoutChapter + noUnit)
            .distinctBy { sourceKey(it) }
    }

    /**
     * Chave canônica de dedup: edição + unidade (artigo/capítulo) + página/§.
     * Mais estável que o `raw` (o mesmo ponto pode vir como "§ 4" ou
     * "ponto 4" do parser/JSON da seção).
     */
    fun sourceKey(ref: RefDetector.DetectedRef): String {
        // Livros podem chegar com editionKey "book|it" (ref persistida) ou
        // "book|it|a:presuncao|10" (detector de texto). A unidade já está
        // nos campos article/chapter/page/§; a edição é só "book|<pubKey>".
        val edition = if (ref.kind == RefDetector.Kind.BOOK) {
            "book|${ref.pubKey}"
        } else {
            ref.editionKey
        }
        val article = normalizeText(ref.article.orEmpty())
        val chapter = normalizeText(ref.chapter.orEmpty())
        val paragraph = ref.paragraph
        // "§10" e "§§10" são a mesma unidade: fim igual ao início não conta.
        val paragraphEnd = ref.paragraphEnd?.takeIf { it != paragraph }
        // Artigo/capítulo + § já identificam a unidade. Página vira detalhe
        // (e pode faltar em uma das origens), então não entra na chave.
        val unit = article.ifBlank { chapter }
        val page = if (unit.isNotBlank() && paragraph != null) null else ref.page
        return buildString {
            append(edition)
            append('|').append(article)
            append('|').append(chapter)
            append('|').append(page ?: -1)
            append('|').append(paragraph ?: -1)
            append('|').append(paragraphEnd ?: -1)
            // C2.5: "g 1/16" (ambíguo) não colapsa com uma ref exata da mesma edição.
            if (ref.ambiguous) append("|amb")
        }
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
                context = ref.context,
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
            context = ref.context,
        )
    }

    private data class MagazineSpec(
        val pubKey: String,
        val editionKey: String,
        val label: String,
    )

    private fun magazineSpec(symbol: String): MagazineSpec? {
        // mwb não é Sentinela/Despertai; mantém o mapeamento próprio.
        Regex("""^mwb(\d{2})\.(\d{1,2})$""").find(symbol)?.let { m ->
            val y = m.groupValues[1].toInt().let { if (it < 100) 2000 + it else it }
            val n = m.groupValues[2].toInt()
            return MagazineSpec("mwb", "mwb|$y|$n", "Apostila Vida e Ministério $n/$y")
        }
        // C2.5: uma única normalização para w/wp/g/gn, inclusive formatos
        // antigos (w94 1/8), modernos (w25.07) e numerados (g16.1, gn 1/24).
        val parsed = MagazineEditionParser.parse(symbol) ?: return null
        return MagazineSpec(
            pubKey = when {
                parsed.family == MagazineFamily.AWAKE && parsed.isIssue -> "gn"
                parsed.family == MagazineFamily.AWAKE -> "g"
                // Edições antigas dia/mês continuam no trilho "w", mesmo no
                // dia 1º; "wp" é só a pública numerada (N.º) moderna.
                parsed.day != null -> "w"
                parsed.kind == MagazineKind.PUBLIC -> "wp"
                else -> "w"
            },
            editionKey = parsed.editionKey(),
            label = magazineLabel(parsed),
        )
    }

    private fun magazineLabel(e: MagazineEdition): String = when {
        e.family == MagazineFamily.AWAKE && e.isIssue ->
            "Despertai! N.º ${e.issue}/${e.year}"
        e.family == MagazineFamily.AWAKE && e.day != null ->
            "Despertai! ${e.day}/${e.month}/${e.year}"
        e.family == MagazineFamily.AWAKE ->
            "Despertai! ${e.month}/${e.year}"
        e.day != null ->
            "A Sentinela ${e.day}/${e.month}/${e.year}"
        e.kind == MagazineKind.PUBLIC ->
            "A Sentinela N.º ${e.month}/${e.year}"
        else ->
            "A Sentinela ${e.month}/${e.year}"
    }

    private fun publicationRaw(ref: PublicationRef): String = buildString {
        val article = ref.article?.trim()?.takeIf { it.isNotBlank() }
        val chapter = ref.chapter?.trim()?.takeIf { it.isNotBlank() }
        val paragraph = ref.paragraph
        // Artigo + § já identificam a unidade. A página no meio do rótulo
        // ("it — Presunção pág. 8 § 10") quebrava o parser do próprio app;
        // o dado continua em `page`, mas o raw segue o formato detectável.
        if (article != null && article != chapter && paragraph != null) {
            append(ref.symbol)
            append(" — ").append(article)
            append(" § ").append(paragraph)
            return@buildString
        }
        append(ref.symbol)
        if (article != null && article != chapter) append(" — ").append(article)
        chapter?.let { append(' ').append(it) }
        ref.page?.let { append(" pág. ").append(it) }
        paragraph?.let {
            if (chapter?.contains("apêndice", ignoreCase = true) == true) {
                append(" ponto ").append(it)
            } else {
                append(" § ").append(it)
            }
        }
    }
}
