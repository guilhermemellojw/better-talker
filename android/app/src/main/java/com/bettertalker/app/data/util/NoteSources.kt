package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

/** Tipo de fonte citada na nota. */
enum class SourceKind { BIBLE, PUBLICATION }

/** Estado de uma fonte no acervo local. */
enum class SourceStatus {
    RESOLVED,
    EDITION_MISSING,
    TEXT_NOT_FOUND,
}

/**
 * Uma fonte citada pela nota, já classificada contra o acervo.
 *
 * @param ref rótulo legível ("w25.07 § 8", "Gên 3:6").
 * @param refStatus status bruto do RefDetector (publicações; null em Bíblia).
 * @param editionKey chave de edição para dedup/inspeção.
 */
data class NoteSource(
    val ref: String,
    val kind: SourceKind,
    val status: SourceStatus,
    val hint: String? = null,
    val downloadUrl: String? = null,
    val fileName: String? = null,
    val refStatus: RefDetector.RefStatus? = null,
    val editionKey: String? = null,
)

/** Resultado do checklist de fontes de uma nota. */
data class NoteSources(
    val sources: List<NoteSource>,
    val resolved: Int,
    val total: Int,
) {
    val hasRefs: Boolean get() = total > 0
    val allResolved: Boolean get() = total > 0 && resolved == total
}

/** Status da Bíblia para um versículo (preenchido pelo repositório). */
data class BibleSourceStatus(
    val available: Boolean,
    val verseFound: Boolean,
)

/** Candidato interno antes de resolver edição/conteúdo. */
data class SourceCandidate(
    val key: String,
    val ref: String,
    val kind: SourceKind,
    val publication: RefDetector.DetectedRef? = null,
    val bible: RefDetector.BibleRef? = null,
)

/**
 * Coleta e classifica as fontes citadas pela nota. A coleta é pura; o
 * repositório resolve edição/conteúdo e chama [toNoteSources].
 *
 * Fontes: outline (refsJson), seções (refs JSON + conteúdo/título) e
 * plainText/mdText. Dedup de publicação por `editionKey + raw`; Bíblia por
 * livro/capítulo/versículo. Puro/testável.
 */
object NoteSourcesResolver {

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

    fun collectCandidates(
        outlineRefsJson: String,
        sections: List<SpeechSection>,
        subPoints: List<SubPoint> = emptyList(),
        noteText: String,
    ): List<SourceCandidate> {
        val publications = mutableListOf<RefDetector.DetectedRef>()
        publications += RefDetector.detectedFromJson(outlineRefsJson)
        publications += RefDetector.detect(noteText)
        sections.forEach { section ->
            publications += RefDetector.detect(section.contentHtml)
            publications += RefDetector.detect(section.title)
            section.publicationRefs.forEach { ref ->
                publicationRefToDetected(ref)?.let { publications += it }
            }
        }
        subPoints.forEach { subPoint ->
            publications += RefDetector.detect(subPoint.outlineText)
            publications += RefDetector.detect(subPoint.developedHtml)
            subPoint.publicationRefs.forEach { ref ->
                publicationRefToDetected(ref)?.let { publications += it }
            }
        }

        val bibles = mutableListOf<RefDetector.BibleRef>()
        bibles += RefDetector.detectBible(noteText)
        sections.forEach { section ->
            bibles += RefDetector.detectBible(section.contentHtml)
            bibles += RefDetector.detectBible(section.title)
            section.bibleRefs.forEach { bibles += RefDetector.detectBible(it) }
        }
        subPoints.forEach { subPoint ->
            bibles += RefDetector.detectBible(subPoint.outlineText)
            bibles += RefDetector.detectBible(subPoint.developedHtml)
            subPoint.bibleRefs.forEach { bibles += RefDetector.detectBible(it) }
        }

        val pubCandidates = publications
            .distinctBy { sourceKey(it) }
            .map { ref ->
                SourceCandidate(
                    key = sourceKey(ref),
                    ref = ref.raw,
                    kind = SourceKind.PUBLICATION,
                    publication = ref,
                )
            }
        val bibleCandidates = bibles
            .distinctBy { "${normalizeText(it.label)}|${it.chapter}|${it.verse}" }
            .map { ref ->
                val canonicalBook = normalizeText(ref.label)
                SourceCandidate(
                    key = "bible|$canonicalBook|${ref.chapter}|${ref.verse}",
                    ref = "${ref.label} ${ref.chapter}:${ref.verse}",
                    kind = SourceKind.BIBLE,
                    bible = ref,
                )
            }
        return pubCandidates + bibleCandidates
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
            paragraph = ref.paragraph,
            paragraphEnd = ref.paragraphEnd,
        )
    }

    /** Mapeia status brutos para o checklist final. Puro/testável. */
    fun toNoteSources(
        candidates: List<SourceCandidate>,
        publicationStatuses: Map<String, RefDetector.RefStatus>,
        bibleStatuses: Map<String, BibleSourceStatus>,
    ): NoteSources {
        val sources = candidates.map { candidate ->
            when (candidate.kind) {
                SourceKind.PUBLICATION -> {
                    val st = publicationStatuses[candidate.key]
                    val editionKey = candidate.publication?.editionKey
                    when {
                        st == null -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = SourceStatus.EDITION_MISSING,
                            hint = "Não foi possível verificar a edição.",
                            editionKey = editionKey,
                        )
                        !st.resolved -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = SourceStatus.EDITION_MISSING,
                            hint = st.hint.ifBlank { "Edição não importada." },
                            downloadUrl = st.downloadUrl.takeIf { it.isNotBlank() },
                            refStatus = st,
                            editionKey = editionKey,
                        )
                        !st.contentResolved -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = SourceStatus.TEXT_NOT_FOUND,
                            hint = st.contentWarning
                                ?: "Edição importada, mas o trecho não foi encontrado no índice.",
                            fileName = st.fileName,
                            refStatus = st,
                            editionKey = editionKey,
                        )
                        else -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = SourceStatus.RESOLVED,
                            fileName = st.fileName,
                            refStatus = st,
                            editionKey = editionKey,
                        )
                    }
                }
                SourceKind.BIBLE -> {
                    val st = bibleStatuses[candidate.key]
                    when {
                        st == null || !st.available -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.BIBLE,
                            status = SourceStatus.EDITION_MISSING,
                            hint = "Baixe a Tradução do Novo Mundo para o Copilot citar o versículo.",
                            downloadUrl = BASE_PUBS.firstOrNull { it.slot == "nwt" }?.landingUrl,
                            editionKey = "bible|nwt",
                        )
                        !st.verseFound -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.BIBLE,
                            status = SourceStatus.TEXT_NOT_FOUND,
                            hint = "Versículo não encontrado no índice da TNM.",
                            fileName = "TNM",
                            editionKey = "bible|nwt",
                        )
                        else -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.BIBLE,
                            status = SourceStatus.RESOLVED,
                            fileName = "TNM",
                            editionKey = "bible|nwt",
                        )
                    }
                }
            }
        }
        return NoteSources(
            sources = sources,
            resolved = sources.count { it.status == SourceStatus.RESOLVED },
            total = sources.size,
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
