package com.bettertalker.app.data.bible

import com.bettertalker.app.data.util.RefDetector

/**
 * UX-REF-1A1 — ocorrência de referência bíblica em texto livre.
 *
 * [start]/[endExclusive] são índices de caractere no texto original. A mesma
 * referência pode aparecer mais de uma vez e cada ocorrência é preservada.
 */
data class BibleReferenceOccurrence(
    val raw: String,
    val start: Int,
    val endExclusive: Int,
    val book: String,
    val chapter: Int,
    val verses: List<Int>,
    val chapterOnly: Boolean,
    val canonicalRefs: List<String>,
    /** Capítulos cobertos; em versículo único contém apenas [chapter]. */
    val chapters: List<Int> = listOf(chapter),
) {
    init {
        require(start >= 0) { "start deve ser >= 0" }
        require(endExclusive > start) { "endExclusive deve ser > start" }
        require(raw.isNotBlank()) { "raw não pode ser vazio" }
        require(book.isNotBlank()) { "book não pode ser vazio" }
        require(chapter > 0) { "chapter deve ser > 0" }
    }

    val range: IntRange get() = start until endExclusive
}

/**
 * Destino de leitura de uma referência bíblica.
 *
 * `sourceAttachmentId` identifica a edição real escolhida pelo leitor.
 * `documentId`/`passageId` da TRUST não são presumidos aqui.
 *
 * UX-REF-1A2: `BibleReaderViewModel.open()` ainda aceita apenas referências
 * textuais; a abertura do leitor deverá fixar ou revalidar
 * [sourceAttachmentId] para não divergir se a Bíblia preferida mudar entre a
 * resolução e o toque.
 */
data class BibleReaderTarget(
    val rawReference: String,
    val canonicalRefs: List<String>,
    val displayRef: String,
    val sourceAttachmentId: String,
    val sourceLabel: String,
    val chapterOnly: Boolean,
    val partial: Boolean,
    val warning: String? = null,
    /** Preenchido apenas quando a evidência do turno já aponta este alvo. */
    val evidenceId: String? = null,
)

sealed interface BibleReferenceResolution {
    data class Available(val target: BibleReaderTarget) : BibleReferenceResolution
    data class Partial(
        val target: BibleReaderTarget,
        val warning: String,
    ) : BibleReferenceResolution

    data class Unavailable(val reason: String) : BibleReferenceResolution
    data class Ambiguous(
        val reason: String,
        val candidates: List<String>,
    ) : BibleReferenceResolution
    data class NotRecognized(val reason: String) : BibleReferenceResolution
}

/**
 * UX-REF-1A1 — detector determinístico de ocorrências bíblicas.
 *
 * Não usa `indexOf`: varre o texto com uma regex de candidato, valida cada
 * candidato pelo [RefDetector] e devolve spans reais. Blocos de código,
 * código inline, links Markdown e URLs são protegidos contra links.
 */
object BibleReferenceSpanDetector {

    private val VERSE_CANDIDATE_RE = Regex(
        """(?<![\p{L}\p{N}_])""" +
            """((?:[1-3]\s*)?[\p{L}]+)\.?\s+(\d{1,3})""" +
            """:(\d{1,3})(?:[A-Za-z])?""" +
            """(?:\s*[-–—]\s*(\d{1,3})(?:[A-Za-z])?)?""" +
            """(?:\s*[,–—-]\s*\d{1,3}(?:[A-Za-z])?""" +
            """(?:\s*[-–—]\s*\d{1,3}(?:[A-Za-z])?)?)*""" +
            """(?![\p{L}\p{N}_:])""",
        RegexOption.IGNORE_CASE,
    )

    private val CHAPTER_CANDIDATE_RE = Regex(
        """(?<![\p{L}\p{N}_])""" +
            """((?:[1-3]\s*)?[\p{L}]{2,})\.?\s+(\d{1,3})""" +
            """(?:\s*[-–—]\s*(\d{1,3}))?""" +
            """(?![\p{L}\p{N}_:])""",
        RegexOption.IGNORE_CASE,
    )

    fun detect(text: String): List<BibleReferenceOccurrence> {
        if (text.isBlank()) return emptyList()
        val protected = protectedRanges(text)
        val verses = VERSE_CANDIDATE_RE.findAll(text)
            .mapNotNull { match ->
                val start = match.range.first
                val end = start + match.value.length
                if (overlapsAny(start, end, protected)) {
                    null
                } else {
                    occurrenceOfVerse(match.value, start, end)
                }
            }
            .toList()
        val chapters = CHAPTER_CANDIDATE_RE.findAll(text)
            .mapNotNull { match ->
                val start = match.range.first
                val end = start + match.value.length
                if (overlapsAny(start, end, protected) ||
                    overlapsAny(start, end, verses.map { it.range })
                ) {
                    null
                } else {
                    occurrenceOfChapter(match.value, start, end)
                }
            }
            .toList()
        return (verses + chapters)
            .distinctBy { it.start to it.endExclusive }
            .sortedBy { it.start }
    }

    private fun overlapsAny(start: Int, end: Int, ranges: List<IntRange>): Boolean =
        ranges.any { start < it.last + 1 && end - 1 >= it.first }

    private fun occurrenceOfVerse(
        raw: String,
        start: Int,
        end: Int,
    ): BibleReferenceOccurrence? {
        val verseRefs = RefDetector.detectBible(raw)
        if (verseRefs.isEmpty()) return null
        val books = verseRefs.map { it.label }.distinct()
        if (books.size != 1) return null
        val chapters = verseRefs.map { it.chapter }.distinct()
        if (chapters.size != 1) return null
        val expanded = expandBibleRefs(raw)
        if (expanded.isEmpty()) return null
        return BibleReferenceOccurrence(
            raw = raw,
            start = start,
            endExclusive = end,
            book = books.single(),
            chapter = chapters.single(),
            verses = verseRefs.map { it.verse }.distinct().sorted(),
            chapterOnly = false,
            canonicalRefs = expanded.map { it.canonical },
        )
    }

    private fun occurrenceOfChapter(
        raw: String,
        start: Int,
        end: Int,
    ): BibleReferenceOccurrence? {
        val chapterRefs = RefDetector.detectBibleChapters(raw)
        if (chapterRefs.isEmpty()) return null
        val expanded = expandBibleChapters(raw)
        if (expanded.isEmpty()) return null
        val chapters = chapterRefs.map { it.chapter }.distinct().sorted()
        if (chapters.size != chapterRefs.size) return null
        return BibleReferenceOccurrence(
            raw = raw,
            start = start,
            endExclusive = end,
            book = chapterRefs.first().label,
            chapter = chapters.first(),
            verses = emptyList(),
            chapterOnly = true,
            canonicalRefs = expanded.map { "${it.bookAbbrev} ${it.chapter}" },
            chapters = chapters,
        )
    }

    /**
     * Regiões que nunca devem virar link: cercas de código, código inline,
     * links Markdown e URLs. Ranges podem se sobrepor; o chamador só precisa
     * testar interseção.
     */
    private fun protectedRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        Regex("```[\\s\\S]*?```").findAll(text).forEach { ranges += it.range }
        Regex("`[^`\\n]*`").findAll(text).forEach { ranges += it.range }
        Regex("\\[[^\\]]*]\\([^)]*\\)").findAll(text).forEach { ranges += it.range }
        Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { ranges += it.range }
        return ranges
    }
}
