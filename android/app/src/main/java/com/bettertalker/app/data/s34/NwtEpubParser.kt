package com.bettertalker.app.data.s34

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Versículo da TNM extraído do EPUB.
 */
data class NwtVerse(
    val book: NwtBook,
    val chapter: Int,
    val verse: Int,
    val text: String,
) {
    /** Ref canônica no padrão do índice do EPUB (ex.: "Êx 1:1"). */
    val canonicalRef: String get() = "${book.abbrev} $chapter:$verse"
    /** Seção de agrupamento (ex.: "Êxodo 1"). */
    val section: String get() = "${book.name} $chapter"
}

/**
 * Parser do EPUB da TNM 2015: cada capítulo é 1 XHTML, versículos marcados
 * por `<span id="chapter{N}_verse{M}"></span>`. Texto do versículo vai do fim
 * de um marcador ao início do próximo (cobre poesia espalhada em `<p>`s).
 * Rodapé (`groupFootnote`), navegação, header e números visíveis são removidos.
 */
object NwtEpubParser {

    private val DOC_ID_REGEX = Regex("""docId-(\d+)""")
    private val VERSE_MARK_REGEX = Regex("""<span id="chapter(\d+)_verse(\d+)"></span>""")
    private val CHAPTER_MARK_REGEX = Regex("""<span id="chapter(\d+)"></span>""")

    /**
     * Extrai todos os versículos de um EPUB da TNM, ordenados por
     * livro/capítulo/versículo.
     */
    fun extractVerses(epubFile: File): List<NwtVerse> {
        val result = mutableListOf<NwtVerse>()
        ZipInputStream(epubFile.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.endsWith(".xhtml", ignoreCase = true) ||
                    name.endsWith(".html", ignoreCase = true)) {
                    val bytes = zin.readBytes()
                    val html = String(bytes, Charsets.UTF_8)
                    extractFromXhtml(html)?.let { result.addAll(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return result.sortedWith(compareBy({ it.book.order }, { it.chapter }, { it.verse }))
    }

    internal fun extractFromXhtml(html: String): List<NwtVerse>? {
        val docIdMatch = DOC_ID_REGEX.find(html) ?: return null
        val docId = docIdMatch.groupValues[1]
        val book = NWT_BOOKS[docId] ?: return null

        val chapter = CHAPTER_MARK_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        val bodyEnd = html.indexOf("<div class=\"groupFootnote\">")
            .takeIf { it > 0 } ?: html.length
        val bodyContent = html.substring(0, bodyEnd)

        val marks = VERSE_MARK_REGEX.findAll(bodyContent).toList()
        if (marks.isEmpty()) return null

        val verses = mutableListOf<NwtVerse>()
        for ((i, mark) in marks.withIndex()) {
            val verseNum = mark.groupValues[2].toInt()
            val start = mark.range.last + 1
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else bodyContent.length
            val rawText = bodyContent.substring(start, end)
            val cleanText = cleanVerseText(rawText)
            if (cleanText.isNotBlank()) {
                verses.add(NwtVerse(book, chapter, verseNum, cleanText))
            }
        }
        return verses
    }

    private fun cleanVerseText(raw: String): String {
        var s = raw
        s = s.replace(Regex("""<strong><sup>\d+</sup></strong>"""), " ")
        s = s.replace(Regex("""<span class="w_ch">.*?</span>"""), " ")
        s = s.replace(Regex("""<a[^>]*epub:type="noteref"[^>]*>.*?</a>"""), "")
        s = s.replace(Regex("""<span id="footnotesource\d+"></span>"""), "")
        s = s.replace(Regex("""<span id="(page|pos)\d*"[^>]*>.*?</span>"""), " ")
        s = s.replace(Regex("""<span id="(page|pos)\d*"[^>]*></span>"""), "")
        s = s.replace(Regex("""<[^>]+>"""), " ")
        s = s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }
}
