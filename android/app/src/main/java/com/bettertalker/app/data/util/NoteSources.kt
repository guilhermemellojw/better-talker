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
    /** Placeholder do DownloadManager criado; o arquivo ainda está chegando. */
    DOWNLOADING,
    /** Arquivo importado; o índice ainda está sendo construído. */
    INDEXING,
}

/** true para estados transitórios (não são "faltando" nem "no acervo"). */
fun SourceStatus.isPending(): Boolean =
    this == SourceStatus.DOWNLOADING || this == SourceStatus.INDEXING

/** true quando a fonte ainda precisa de ação do usuário. */
fun SourceStatus.isMissing(): Boolean =
    this == SourceStatus.EDITION_MISSING || this == SourceStatus.TEXT_NOT_FOUND

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
    /** Bíblia: nome canônico do livro ("Jeremias"). */
    val book: String? = null,
    /** Bíblia: capítulo/versículo para ordenar dentro do livro. */
    val chapter: Int? = null,
    val verse: Int? = null,
    /** Publicação: chave de agrupamento ("nwt", "w25.07", "lmd", "th"). */
    val publicationKey: String? = null,
    /** Rótulo humano da edição ("TNM", "A Sentinela — julho de 2025"). */
    val publicationLabel: String? = null,
)

/** Resultado do checklist de fontes de uma nota. */
data class NoteSources(
    val sources: List<NoteSource>,
    val resolved: Int,
    val total: Int,
) {
    val hasRefs: Boolean get() = total > 0
    val allResolved: Boolean get() = total > 0 && resolved == total
    /** Fontes em download/indexação (transitórias). */
    val pending: Int get() = sources.count { it.status.isPending() }
}

/** Status agregado de um grupo do sheet. */
enum class GroupStatus { OK, PARTIAL, MISSING }

/** Subgrupo por livro dentro da TNM. */
data class BookGroup(
    val book: String,
    val label: String,
    val sources: List<NoteSource>,
    val resolved: Int,
    val total: Int,
    val status: GroupStatus,
) {
    /** Fontes do livro em download/indexação. */
    val pendingCount: Int get() = sources.count { it.status.isPending() }

    /** Todas as fontes ainda não resolvidas estão em andamento. */
    val allPending: Boolean
        get() = pendingCount > 0 && resolved + pendingCount == total
}

/** Grupo de fontes (publicação; TNM contém subgrupos por livro). */
data class SourceGroup(
    val key: String,
    val label: String,
    val kind: SourceKind,
    val sources: List<NoteSource>,
    val resolved: Int,
    val total: Int,
    val status: GroupStatus,
    val bookGroups: List<BookGroup> = emptyList(),
) {
    /** Fontes do grupo em download/indexação. */
    val pendingCount: Int get() = sources.count { it.status.isPending() }

    /** Todas as fontes ainda não resolvidas estão em andamento. */
    val allPending: Boolean
        get() = pendingCount > 0 && resolved + pendingCount == total
}

/**
 * Agrupa o checklist por publicação. A TNM é um único grupo ("nwt") com
 * subgrupos por livro; as demais publicações ficam por edição (w25.07, lmd…).
 *
 * Ordem: publicações específicas com faltantes primeiro (ação), depois TNM,
 * depois publicações específicas OK. Puro/testável.
 */
fun groupNoteSources(sources: List<NoteSource>): List<SourceGroup> {
    if (sources.isEmpty()) return emptyList()

    val bibleItems = sources.filter { it.kind == SourceKind.BIBLE }
    val nwt = if (bibleItems.isEmpty()) {
        null
    } else {
        val books = bibleItems
            .groupBy { it.book ?: "Outros" }
            .map { (book, items) ->
                val ordered = items.sortedWith(
                    compareBy(
                        { it.chapter ?: Int.MAX_VALUE },
                        { it.verse ?: Int.MAX_VALUE },
                    ),
                )
                BookGroup(
                    book = book,
                    label = if (book == "Outros") "Outras referências bíblicas" else book,
                    sources = ordered,
                    resolved = ordered.count { it.status == SourceStatus.RESOLVED },
                    total = ordered.size,
                    status = groupStatus(ordered),
                )
            }
            .sortedBy { bibleBookOrder(it.label) }
        SourceGroup(
            key = "pub:nwt",
            label = "TNM",
            kind = SourceKind.BIBLE,
            sources = books.flatMap { it.sources },
            resolved = bibleItems.count { it.status == SourceStatus.RESOLVED },
            total = bibleItems.size,
            status = groupStatus(bibleItems),
            bookGroups = books,
        )
    }

    val pubGroups = sources.withIndex()
        .filter { it.value.kind == SourceKind.PUBLICATION }
        .groupBy { it.value.publicationKey ?: "outros" }
    val publications = pubGroups.map { (key, indexed) ->
        val items = indexed
            .map { it.value }
            .sortedWith(
                compareBy(
                    { it.refStatus?.ref?.page ?: Int.MAX_VALUE },
                    { it.refStatus?.ref?.paragraph ?: Int.MAX_VALUE },
                ),
            )
        val first = items.first()
        SourceGroup(
            key = "pub:$key",
            label = first.publicationLabel
                ?: first.publicationKey
                ?: "Outras publicações",
            kind = SourceKind.PUBLICATION,
            sources = items,
            resolved = items.count { it.status == SourceStatus.RESOLVED },
            total = items.size,
            status = groupStatus(items),
        )
    }.sortedBy { group ->
        pubGroups[group.key.removePrefix("pub:")]?.firstOrNull()?.index ?: Int.MAX_VALUE
    }

    val missingSpecific = publications.filter { it.status != GroupStatus.OK }
    val resolvedSpecific = publications.filter { it.status == GroupStatus.OK }
    return missingSpecific + listOfNotNull(nwt) + resolvedSpecific
}

private fun groupStatus(items: List<NoteSource>): GroupStatus {
    val resolved = items.count { it.status == SourceStatus.RESOLVED }
    return when {
        resolved == items.size -> GroupStatus.OK
        resolved == 0 -> GroupStatus.MISSING
        else -> GroupStatus.PARTIAL
    }
}

/** Ordem canônica do livro (1..66); desconhecido/outros = Int.MAX_VALUE. */
fun bibleBookOrder(book: String?): Int {
    if (book.isNullOrBlank() || book == "Outras referências bíblicas") {
        return Int.MAX_VALUE
    }
    val normalized = normalizeText(book)
    val abbrev = com.bettertalker.app.data.s34.normalizeBibleBookName(book)
    return com.bettertalker.app.data.s34.NWT_BOOKS.values
        .firstOrNull {
            normalizeText(it.name) == normalized || it.abbrev == abbrev
        }?.order ?: Int.MAX_VALUE
}

/** Chave de agrupamento da publicação (w25.07, lmd, th…). */
fun publicationDisplayKey(ref: RefDetector.DetectedRef?): String? {
    if (ref == null) return null
    if (ref.kind == RefDetector.Kind.BOOK) return ref.pubKey
    val parts = ref.editionKey.split("|")
    if (parts.size < 3) return ref.pubKey
    val key = parts[0]
    val year = parts[1]
    val num = parts[2]
    val day = parts.getOrNull(3)
    val yy = year.takeLast(2)
    return when {
        key == "w" && day != null -> "w$yy $day/$num"
        key == "g" && day != null -> "g$yy $day/$num"
        key == "w" -> "w$yy.${num.padStart(2, '0')}"
        key == "wp" -> "wp$yy.${num.padStart(2, '0')}"
        key == "g" -> "g $num/$yy"
        key == "gn" -> "gn $num/$yy"
        key == "mwb" -> "mwb$yy.${num.padStart(2, '0')}"
        else -> ref.pubKey
    }
}

/** Rótulo humano da edição ("A Sentinela — julho de 2025"). */
fun publicationEditionLabel(ref: RefDetector.DetectedRef?): String? {
    if (ref == null) return null
    if (ref.kind == RefDetector.Kind.BOOK) {
        return BASE_PUBS.firstOrNull { it.slot == ref.pubKey }?.title
            ?: PubCatalog.titleOf(ref.pubKey)
            ?: ref.label
    }
    val parts = ref.editionKey.split("|")
    if (parts.size < 3) return ref.label
    val key = parts[0]
    val year = parts[1].toIntOrNull() ?: 0
    val num = parts[2].toIntOrNull() ?: 0
    val day = parts.getOrNull(3)?.toIntOrNull()
    return when (key) {
        "w" -> if (day != null) {
            "A Sentinela — $day/$num/$year"
        } else {
            "A Sentinela — ${monthName(num)} de $year"
        }
        "wp" -> "A Sentinela — N.º $num/$year"
        "g" -> "Despertai! — ${monthName(num)} de $year"
        "gn" -> "Despertai! — N.º $num/$year"
        "mwb" -> "Apostila Vida e Ministério — ${monthName(num)} de $year"
        else -> ref.label
    }
}

private fun monthName(month: Int): String = when (month) {
    1 -> "janeiro"; 2 -> "fevereiro"; 3 -> "março"; 4 -> "abril"
    5 -> "maio"; 6 -> "junho"; 7 -> "julho"; 8 -> "agosto"
    9 -> "setembro"; 10 -> "outubro"; 11 -> "novembro"; 12 -> "dezembro"
    else -> "edição $month"
}

/** Status da Bíblia para um versículo (preenchido pelo repositório). */
data class BibleSourceStatus(
    val available: Boolean,
    val verseFound: Boolean,
    /** TNM ainda baixando/indexando (transitório). */
    val pending: SourceStatus? = null,
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

    /** Chave canônica de dedup (mesma do coletor único). */
    fun sourceKey(ref: RefDetector.DetectedRef): String = NoteRefsCollector.sourceKey(ref)

    fun collectCandidates(
        outlineRefsJson: String,
        sections: List<SpeechSection>,
        subPoints: List<SubPoint> = emptyList(),
        noteText: String,
        noteTitle: String = "",
    ): List<SourceCandidate> = candidatesOf(
        NoteRefsCollector.collect(
            noteTitle = noteTitle,
            mdText = noteText,
            outlineRefsJson = outlineRefsJson,
            sections = sections.map { it.toSectionRefs() },
            subPoints = subPoints.map { it.toSubPointRefs() },
        ),
    )

    /** Converte as refs únicas em candidatos do checklist. Puro/testável. */
    fun candidatesOf(refs: NoteRefs): List<SourceCandidate> {
        val pubCandidates = refs.publications.map { ref ->
            SourceCandidate(
                key = sourceKey(ref),
                ref = ref.raw,
                kind = SourceKind.PUBLICATION,
                publication = ref,
            )
        }
        val bibleCandidates = refs.bibles.map { ref ->
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

    /** Converte `PublicationRef` persistido em `DetectedRef` (coletor único). */
    fun publicationRefToDetected(ref: PublicationRef): RefDetector.DetectedRef? =
        NoteRefsCollector.publicationRefToDetected(ref)

    /** Mapeia status brutos para o checklist final. Puro/testável. */
    fun toNoteSources(
        candidates: List<SourceCandidate>,
        publicationStatuses: Map<String, RefDetector.RefStatus>,
        bibleStatuses: Map<String, BibleSourceStatus>,
        /** Estados transitórios por chave de candidato (download/indexação). */
        pendingStatuses: Map<String, SourceStatus> = emptyMap(),
    ): NoteSources {
        val sources = candidates.map { candidate ->
            val editionKey = candidate.publication?.editionKey
            val publicationKey = publicationDisplayKey(candidate.publication)
            val publicationLabel = publicationEditionLabel(candidate.publication)
            val book = candidate.bible?.label
            when (candidate.kind) {
                SourceKind.PUBLICATION -> {
                    val st = publicationStatuses[candidate.key]
                    val pending = pendingStatuses[candidate.key]
                    when {
                        st?.resolved == true && !st.contentResolved -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = SourceStatus.TEXT_NOT_FOUND,
                            hint = st.contentWarning
                                ?: "Edição importada, mas o trecho não foi encontrado no índice.",
                            fileName = st.fileName,
                            refStatus = st,
                            editionKey = editionKey,
                            publicationKey = publicationKey,
                            publicationLabel = publicationLabel,
                        )
                        st?.resolved == true -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = SourceStatus.RESOLVED,
                            fileName = st.fileName,
                            refStatus = st,
                            editionKey = editionKey,
                            publicationKey = publicationKey,
                            publicationLabel = publicationLabel,
                        )
                        pending != null -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = pending,
                            hint = pendingHint(pending),
                            downloadUrl = st?.downloadUrl?.takeIf { it.isNotBlank() },
                            refStatus = st,
                            editionKey = editionKey,
                            publicationKey = publicationKey,
                            publicationLabel = publicationLabel,
                        )
                        st == null -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = SourceStatus.EDITION_MISSING,
                            hint = "Não foi possível verificar a edição.",
                            editionKey = editionKey,
                            publicationKey = publicationKey,
                            publicationLabel = publicationLabel,
                        )
                        else -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.PUBLICATION,
                            status = SourceStatus.EDITION_MISSING,
                            hint = st.hint.ifBlank { "Edição não importada." },
                            downloadUrl = st.downloadUrl.takeIf { it.isNotBlank() },
                            refStatus = st,
                            editionKey = editionKey,
                            publicationKey = publicationKey,
                            publicationLabel = publicationLabel,
                        )
                    }
                }
                SourceKind.BIBLE -> {
                    val st = bibleStatuses[candidate.key]
                    when {
                        st?.available == true && !st.verseFound -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.BIBLE,
                            status = SourceStatus.TEXT_NOT_FOUND,
                            hint = "Versículo não encontrado no índice da TNM.",
                            fileName = "TNM",
                            editionKey = "bible|nwt",
                            book = book,
                            chapter = candidate.bible?.chapter,
                            verse = candidate.bible?.verse,
                            publicationKey = "nwt",
                            publicationLabel = "TNM",
                        )
                        st?.available == true -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.BIBLE,
                            status = SourceStatus.RESOLVED,
                            fileName = "TNM",
                            editionKey = "bible|nwt",
                            book = book,
                            chapter = candidate.bible?.chapter,
                            verse = candidate.bible?.verse,
                            publicationKey = "nwt",
                            publicationLabel = "TNM",
                        )
                        st?.pending != null -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.BIBLE,
                            status = st.pending,
                            hint = pendingHint(st.pending),
                            downloadUrl = BASE_PUBS.firstOrNull { it.slot == "nwt" }?.landingUrl,
                            editionKey = "bible|nwt",
                            book = book,
                            chapter = candidate.bible?.chapter,
                            verse = candidate.bible?.verse,
                            publicationKey = "nwt",
                            publicationLabel = "TNM",
                        )
                        else -> NoteSource(
                            ref = candidate.ref,
                            kind = SourceKind.BIBLE,
                            status = SourceStatus.EDITION_MISSING,
                            hint = "Baixe a Tradução do Novo Mundo para o Copilot citar o versículo.",
                            downloadUrl = BASE_PUBS.firstOrNull { it.slot == "nwt" }?.landingUrl,
                            editionKey = "bible|nwt",
                            book = book,
                            chapter = candidate.bible?.chapter,
                            verse = candidate.bible?.verse,
                            publicationKey = "nwt",
                            publicationLabel = "TNM",
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

    private fun pendingHint(status: SourceStatus): String = when (status) {
        SourceStatus.DOWNLOADING ->
            "Download iniciado — acompanhe na Biblioteca."
        SourceStatus.INDEXING ->
            "Indexando — o Copilot usa o trecho quando terminar."
        else -> ""
    }
}
