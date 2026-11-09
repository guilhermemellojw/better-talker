package com.bettertalker.app.domain.bible

import com.bettertalker.app.data.bible.ExpandedBibleRef
import com.bettertalker.app.data.bible.displayBibleRefs
import com.bettertalker.app.data.bible.expandBibleRefs

/**
 * v5.2 — leitor TNM por referência livre.
 *
 * O caminho é próprio: expande faixas/listas com `RefDetector`, canoniza para
 * a chave do índice TNM, escolhe a TNM base (ou uma Bíblia alternativa
 * indexada), busca os versículos em lote e devolve um resultado honesto.
 * Não altera o `resolveBible` do dossiê (dívida registrada).
 */
class BiblePassageReader(
    private val source: BiblePassageSource,
    private val maxVerses: Int = MAX_VERSES,
) {

    suspend fun read(raw: String): BiblePassage = read(listOf(raw))

    suspend fun read(rawRefs: List<String>): BiblePassage {
        val cleaned = rawRefs.map { it.trim() }.filter { it.isNotEmpty() }
        val requested = cleaned.joinToString("; ")
        val expanded = expandBibleRefs(cleaned)
        if (expanded.isEmpty()) {
            return BiblePassage(
                requested = requested,
                displayRef = requested,
                verses = emptyList(),
                status = BiblePassageStatus.MISSING_CORPUS,
                sourceLabel = "",
                warning = "Referência bíblica não reconhecida.",
            )
        }

        val displayRef = displayBibleRefs(expanded)
        val bible = source.preferredSource()
            ?: return BiblePassage(
                requested = requested,
                displayRef = displayRef,
                verses = emptyList(),
                status = BiblePassageStatus.MISSING_CORPUS,
                sourceLabel = "",
                warning = "A TNM não está no acervo.",
                totalVerses = expanded.size,
                needsLibrary = true,
            )

        val limited = expanded.take(maxVerses.coerceAtLeast(1))
        val texts = source.texts(bible.attachmentId, limited.map { it.canonical })
        val verses = limited.mapNotNull { ref -> verseOf(ref, texts[ref.canonical]) }
        val total = expanded.size
        val truncated = total > limited.size
        val status = when {
            verses.isEmpty() -> BiblePassageStatus.UNRESOLVED
            truncated || verses.size < limited.size -> BiblePassageStatus.PARTIAL
            else -> BiblePassageStatus.RESOLVED
        }
        return BiblePassage(
            requested = requested,
            displayRef = displayRef,
            verses = verses,
            status = status,
            sourceLabel = bible.label,
            warning = warningFor(
                found = verses.size,
                total = total,
                limited = limited.size,
                truncated = truncated,
                status = status,
            ),
            totalVerses = total,
            truncated = truncated,
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

    private fun warningFor(
        found: Int,
        total: Int,
        limited: Int,
        truncated: Boolean,
        status: BiblePassageStatus,
    ): String? = when {
        truncated && found < limited ->
            "Mostrando os primeiros $limited de $total versículos; $found encontrados."
        truncated ->
            "Mostrando os primeiros $limited de $total versículos."
        status == BiblePassageStatus.UNRESOLVED ->
            "Nenhum versículo encontrado no acervo."
        found < total -> "$found de $total versículos encontrados."
        else -> null
    }

    companion object {
        const val MAX_VERSES = 200
    }
}
