package com.bettertalker.app.domain.bible

/** v5.2 — origem indexada da leitura (TNM base ou Bíblia alternativa). */
data class BibleSource(
    val attachmentId: String,
    val label: String,
    val isTnm: Boolean,
)

/**
 * Seam de dados do leitor. A implementação Room usa `PassageDao`/`AttachmentDao`;
 * os testes usam um fake puro.
 */
interface BiblePassageSource {
    suspend fun preferredSource(): BibleSource?
    suspend fun texts(sourceId: String, canonicalRefs: List<String>): Map<String, String>

    /** v5.2.1: versículos de um capítulo inteiro, na ordem do índice. */
    suspend fun chapterTexts(sourceId: String, prefix: String): Map<String, String>
}

enum class BiblePassageStatus { RESOLVED, PARTIAL, UNRESOLVED, MISSING_CORPUS }

data class BibleVerse(
    val ref: String,
    val book: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
)

data class BiblePassageGroup(
    val book: String,
    val chapter: Int,
    val verses: List<BibleVerse>,
)

data class BiblePassage(
    val requested: String,
    val displayRef: String,
    val verses: List<BibleVerse>,
    val status: BiblePassageStatus,
    val sourceLabel: String,
    val warning: String? = null,
    /** Quantidade de versículos pedidos (antes do teto de 200). */
    val totalVerses: Int = verses.size,
    val truncated: Boolean = false,
    /** true = não há Bíblia no acervo; a UI oferece “Abrir Biblioteca”. */
    val needsLibrary: Boolean = false,
    /** v5.2.1: pedido composto só por capítulos inteiros. */
    val chapterOnly: Boolean = false,
    /** Quantidade de capítulos/refs pedidos (para status parcial). */
    val totalRequests: Int = totalVerses,
    val foundRequests: Int = verses.size,
) {
    /** Versículos agrupados por livro/capítulo, na ordem de entrada. */
    val groups: List<BiblePassageGroup>
        get() {
            val out = LinkedHashMap<Pair<String, Int>, MutableList<BibleVerse>>()
            verses.forEach { verse ->
                out.getOrPut(verse.book to verse.chapter) { mutableListOf() }.add(verse)
            }
            return out.map { (key, list) ->
                BiblePassageGroup(book = key.first, chapter = key.second, verses = list)
            }
        }

    /** TTS lê o texto corrido, sem números de versículo. */
    val speechText: String get() = verses.joinToString("\n") { it.text }

    /** Cópia com cabeçalhos de capítulo e números de versículo. */
    val copyText: String
        get() = groups.joinToString("\n\n") { group ->
            buildString {
                append(group.book).append(' ').append(group.chapter)
                group.verses.forEach { verse ->
                    append('\n').append(verse.verse).append(' ').append(verse.text)
                }
            }
        }

    val foundLabel: String
        get() = if (chapterOnly) {
            "$foundRequests de $totalRequests capítulos encontrados"
        } else {
            "${verses.size} de $totalVerses versículos encontrados"
        }
}
