package com.bettertalker.app.domain.bible

import com.bettertalker.app.data.bible.ExpandedBibleChapter
import com.bettertalker.app.data.bible.ExpandedBibleRef
import com.bettertalker.app.data.bible.displayBibleRequest
import com.bettertalker.app.data.bible.expandBibleChapters
import com.bettertalker.app.data.bible.expandBibleRefs

/**
 * v5.2/v5.2.1 — leitor TNM por referência livre.
 *
 * Aceita versículos/faixas/listas e capítulos inteiros/faixas de capítulos.
 * Expande com `RefDetector`, canoniza para a chave do índice TNM, escolhe a
 * TNM base (ou uma Bíblia alternativa indexada), busca os versículos em lote
 * e devolve um resultado honesto. Não altera o `resolveBible` do dossiê.
 */
class BiblePassageReader(
    private val source: BiblePassageSource,
    private val maxVerses: Int = MAX_VERSES,
) {

    suspend fun read(raw: String): BiblePassage = read(listOf(raw))

    suspend fun read(rawRefs: List<String>): BiblePassage {
        val cleaned = rawRefs.map { it.trim() }.filter { it.isNotEmpty() }
        val requested = cleaned.joinToString("; ")
        val verseRefs = expandBibleRefs(cleaned)
        val chapterRefs = expandBibleChapters(cleaned)
        if (verseRefs.isEmpty() && chapterRefs.isEmpty()) {
            return BiblePassage(
                requested = requested,
                displayRef = requested,
                verses = emptyList(),
                status = BiblePassageStatus.MISSING_CORPUS,
                sourceLabel = "",
                warning = "Referência bíblica não reconhecida.",
            )
        }

        val displayRef = displayBibleRequest(verseRefs, chapterRefs)
        val totalRequests = verseRefs.size + chapterRefs.size
        val chapterOnly = chapterRefs.isNotEmpty() && verseRefs.isEmpty()
        val bible = source.preferredSource()
            ?: return BiblePassage(
                requested = requested,
                displayRef = displayRef,
                verses = emptyList(),
                status = BiblePassageStatus.MISSING_CORPUS,
                sourceLabel = "",
                warning = "A TNM não está no acervo.",
                totalVerses = verseRefs.size,
                needsLibrary = true,
                chapterOnly = chapterOnly,
                totalRequests = totalRequests,
                foundRequests = 0,
            )

        val verseTexts = if (verseRefs.isEmpty()) {
            emptyMap()
        } else {
            source.texts(bible.attachmentId, verseRefs.map { it.canonical })
        }
        val verseModels = verseRefs.mapNotNull { ref -> verseOf(ref, verseTexts[ref.canonical]) }

        val chapterModels = mutableListOf<BibleVerse>()
        var foundChapters = 0
        var totalChapterVerses = 0
        chapterRefs.forEach { chapter ->
            val prefix = "${chapter.canonicalPrefix}%"
            val texts = source.chapterTexts(bible.attachmentId, prefix)
            if (texts.isNotEmpty()) foundChapters++
            totalChapterVerses += texts.size
            texts.forEach { (ref, text) ->
                chapterVerseOf(chapter, ref, text)?.let { chapterModels += it }
            }
        }

        val all = LinkedHashMap<String, BibleVerse>()
        verseModels.forEach { all.putIfAbsent(it.ref, it) }
        chapterModels.forEach { all.putIfAbsent(it.ref, it) }
        val ordered = all.values.toList()
        val limited = ordered.take(maxVerses.coerceAtLeast(1))
        val truncated = ordered.size > limited.size
        val foundRequests = verseModels.size + foundChapters
        val status = when {
            limited.isEmpty() -> BiblePassageStatus.UNRESOLVED
            truncated || foundRequests < totalRequests -> BiblePassageStatus.PARTIAL
            else -> BiblePassageStatus.RESOLVED
        }
        val totalVerses = verseRefs.size + totalChapterVerses
        return BiblePassage(
            requested = requested,
            displayRef = displayRef,
            verses = limited,
            status = status,
            sourceLabel = bible.label,
            warning = warningFor(
                foundVerses = limited.size,
                totalVerses = ordered.size,
                foundRequests = foundRequests,
                totalRequests = totalRequests,
                foundChapters = foundChapters,
                totalChapters = chapterRefs.size,
                chapterOnly = chapterOnly,
                truncated = truncated,
                status = status,
            ),
            totalVerses = totalVerses,
            truncated = truncated,
            chapterOnly = chapterOnly,
            totalRequests = totalRequests,
            foundRequests = foundRequests,
        )
    }

    private fun verseOf(ref: ExpandedBibleRef, text: String?): BibleVerse? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return BibleVerse(
            ref = ref.canonical,
            book = ref.bookLabel,
            chapter = ref.chapter,
            verse = ref.verse,
            text = clean,
        )
    }

    private fun chapterVerseOf(
        chapter: ExpandedBibleChapter,
        ref: String,
        text: String?,
    ): BibleVerse? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        val verse = ref.substringAfterLast(':', "").toIntOrNull() ?: return null
        return BibleVerse(
            ref = ref,
            book = chapter.bookLabel,
            chapter = chapter.chapter,
            verse = verse,
            text = clean,
        )
    }

    private fun warningFor(
        foundVerses: Int,
        totalVerses: Int,
        foundRequests: Int,
        totalRequests: Int,
        foundChapters: Int,
        totalChapters: Int,
        chapterOnly: Boolean,
        truncated: Boolean,
        status: BiblePassageStatus,
    ): String? = when {
        truncated && foundVerses < totalVerses ->
            "Mostrando os primeiros $foundVerses de $totalVerses versículos."
        truncated ->
            "Mostrando os primeiros $foundVerses de $totalVerses versículos."
        status == BiblePassageStatus.UNRESOLVED ->
            "Nenhum versículo encontrado no acervo."
        chapterOnly && foundChapters < totalChapters ->
            "$foundChapters de $totalChapters capítulos encontrados."
        foundRequests < totalRequests ->
            "$foundRequests de $totalRequests referências encontradas."
        else -> null
    }

    companion object {
        const val MAX_VERSES = 200
    }
}
