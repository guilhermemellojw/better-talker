package com.bettertalker.app.data.bible

import com.bettertalker.app.data.s34.normalizeBibleBookName
import com.bettertalker.app.data.util.RefDetector

/**
 * v5.2 — referência bíblica já expandida e canonizada para o índice TNM.
 *
 * `bookLabel` é o nome legível ("Ezequiel"); `bookAbbrev` é a chave do
 * índice ("Ez"). O `RefDetector` expande faixas/listas ("Ez 1:16-18",
 * "Gên 1:26,31") antes desta camada.
 */
data class ExpandedBibleRef(
    val bookLabel: String,
    val bookAbbrev: String,
    val chapter: Int,
    val verse: Int,
) {
    val canonical: String get() = "$bookAbbrev $chapter:$verse"
    val display: String get() = "$bookLabel $chapter:$verse"
}

/** Expande uma ref livre em uma lista ordenada, sem duplicatas. */
fun expandBibleRefs(raw: String): List<ExpandedBibleRef> =
    expandBibleRefs(listOf(raw))

/** Expande várias refs preservando a ordem de entrada e removendo duplicatas. */
fun expandBibleRefs(rawRefs: List<String>): List<ExpandedBibleRef> {
    val out = mutableListOf<ExpandedBibleRef>()
    val seen = mutableSetOf<String>()
    rawRefs.forEach { raw ->
        RefDetector.detectBible(raw).forEach { bible ->
            val abbrev = normalizeBibleBookName(bible.label) ?: return@forEach
            val ref = ExpandedBibleRef(
                bookLabel = bible.label,
                bookAbbrev = abbrev,
                chapter = bible.chapter,
                verse = bible.verse,
            )
            if (seen.add(ref.canonical)) out += ref
        }
    }
    return out
}

fun canonicalBibleRefs(raw: String): List<String> =
    expandBibleRefs(raw).map { it.canonical }

fun canonicalBibleRefs(rawRefs: List<String>): List<String> =
    expandBibleRefs(rawRefs).map { it.canonical }

/** Ref legível, com faixas compactadas ("Ezequiel 1:16-18; João 3:16"). */
fun displayBibleRefs(refs: List<ExpandedBibleRef>): String {
    if (refs.isEmpty()) return ""
    val byBook = LinkedHashMap<String, MutableList<ExpandedBibleRef>>()
    refs.forEach { ref ->
        byBook.getOrPut(ref.bookLabel) { mutableListOf() }.add(ref)
    }
    return byBook.entries.joinToString("; ") { (book, bookRefs) ->
        val byChapter = LinkedHashMap<Int, MutableList<Int>>()
        bookRefs.forEach { ref ->
            byChapter.getOrPut(ref.chapter) { mutableListOf() }.add(ref.verse)
        }
        val chapters = byChapter.entries.joinToString("; ") { (chapter, verses) ->
            "$chapter:${compressVerses(verses)}"
        }
        "$book $chapters"
    }
}

fun displayBibleRefsFor(raw: String): String =
    displayBibleRefs(expandBibleRefs(raw))

fun displayBibleRefsFor(rawRefs: List<String>): String =
    displayBibleRefs(expandBibleRefs(rawRefs))

/**
 * Divide a entrada do editor em refs. Vírgula seguida de número pertence à
 * lista do mesmo versículo ("Gên 1:26,31"); vírgula seguida de livro separa
 * refs ("Sal 1:1, Jo 3:16"). Semiconto sempre separa.
 */
fun splitBibleRefsInput(raw: String): List<String> =
    raw.split(Regex(""";\s*|,\s*(?=[1-3]?\s*[A-Za-zÀ-ÿ])"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** "16, 17, 18, 20" → "16-18, 20"; entradas duplicatas são removidas. */
private fun compressVerses(verses: List<Int>): String {
    val sorted = verses.distinct().sorted()
    if (sorted.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var start = sorted.first()
    var last = start
    for (verse in sorted.drop(1)) {
        if (verse == last + 1) {
            last = verse
        } else {
            parts += if (start == last) "$start" else "$start-$last"
            start = verse
            last = verse
        }
    }
    parts += if (start == last) "$start" else "$start-$last"
    return parts.joinToString(", ")
}
