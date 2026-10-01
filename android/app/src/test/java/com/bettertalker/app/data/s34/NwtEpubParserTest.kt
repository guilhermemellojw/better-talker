package com.bettertalker.app.data.s34

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NwtEpubParserTest {

    private fun xhtml(body: String, docId: String = "1001061106") =
        """<html><body class="jwac docId-$docId">$body</body></html>"""

    private val chapter1 = xhtml(
        """
        <span id="chapter1"></span>
        <header><p>ÊXODO</p></header>
        <p><span class="w_ch"><strong>1</strong> </span><span id="chapter1_verse1"></span><strong><sup>1</sup></strong>Estes são os nomes dos filhos de Israel. <span id="footnotesource1"></span><a epub:type="noteref" href="#footnote1">*</a></p>
        <p><span id="chapter1_verse2"></span><strong><sup>2</sup></strong>Rúben, Simeão, Levi e Judá.</p>
        <p><span id="chapter1_verse3"></span><strong><sup>3</sup></strong>Issacar, Zebulão e Benjamim.</p>
        <div class="groupFootnote"><p>Nota de rodapé que deve ser ignorada.</p></div>
        <p class="w_navigation"><a href="x">Anterior</a></p>
        """.trimIndent()
    )

    @Test
    fun extractFromXhtml_singleVerse() {
        val html = xhtml(
            """<span id="chapter20"></span><p><span id="chapter20_verse1"></span><strong><sup>1</sup></strong>Então Deus disse todas estas palavras:</p>"""
        )
        val verses = NwtEpubParser.extractFromXhtml(html)!!
        assertEquals(1, verses.size)
        assertEquals(20, verses.single().chapter)
        assertEquals(1, verses.single().verse)
    }

    @Test
    fun extractFromXhtml_multipleVersesInOneParagraph() {
        val verses = NwtEpubParser.extractFromXhtml(chapter1)!!
        assertEquals(listOf(1, 2, 3), verses.map { it.verse })
        assertTrue(verses[1].text.contains("Rúben"))
    }

    @Test
    fun extractFromXhtml_poetryVersesAcrossParagraphs() {
        val html = xhtml(
            """
            <span id="chapter15"></span>
            <p><span id="chapter15_verse1"></span><strong><sup>1</sup></strong>Então Moisés cantou:</p>
            <p>Cantarei a Jeová, pois ele é exaltado.</p>
            <p><span id="chapter15_verse2"></span><strong><sup>2</sup></strong>Jeová é a minha força.</p>
            <p><span id="chapter15_verse3"></span><strong><sup>3</sup></strong>Jeová é guerreiro.</p>
            """.trimIndent()
        )
        val verses = NwtEpubParser.extractFromXhtml(html)!!
        assertEquals(3, verses.size)
        assertTrue(verses[0].text.contains("Cantarei a Jeová"))
        assertEquals("Êx 15:2", verses[1].canonicalRef)
    }

    @Test
    fun extractFromXhtml_ignoresFootnotes() {
        val verses = NwtEpubParser.extractFromXhtml(chapter1)!!
        assertTrue(verses.none { it.text.contains("rodapé") })
        assertTrue(verses.none { it.text.contains("*") })
    }

    @Test
    fun extractFromXhtml_removesVerseNumber() {
        val verses = NwtEpubParser.extractFromXhtml(chapter1)!!
        val v2 = verses.first { it.verse == 2 }
        assertTrue(!v2.text.startsWith("2 "))
        assertTrue(v2.text.startsWith("Rúben"))
    }

    @Test
    fun extractFromXhtml_removesChapterNumber() {
        val verses = NwtEpubParser.extractFromXhtml(chapter1)!!
        val v1 = verses.first { it.verse == 1 }
        assertTrue(!v1.text.startsWith("1 "))
        assertTrue(v1.text.startsWith("Estes são"))
    }

    @Test
    fun extractFromXhtml_returnsNullForNonNwtDocId() {
        val html = xhtml("""<span id="chapter1"></span><p><span id="chapter1_verse1"></span>Texto.</p>""",
            docId = "9999999999")
        assertNull(NwtEpubParser.extractFromXhtml(html))
    }

    @Test
    fun extractFromXhtml_correctCanonicalRef() {
        val verses = NwtEpubParser.extractFromXhtml(chapter1)!!
        val v1 = verses.first { it.verse == 1 }
        assertEquals("Êx 1:1", v1.canonicalRef)
        assertEquals("Êxodo 1", v1.section)
    }

    @Test
    fun extractFromXhtml_preservesEmText() {
        val html = xhtml(
            """<span id="chapter28"></span><p><span id="chapter28_verse19"></span><strong><sup>19</sup></strong>E porás <em>léshem,</em> e ônix no peitoral.</p>"""
        )
        val verses = NwtEpubParser.extractFromXhtml(html)!!
        assertTrue(verses.single().text.contains("léshem,"))
        assertEquals("Êx 28:19", verses.single().canonicalRef)
    }

    @Test
    fun extractFromXhtml_verseWithInternalQuote() {
        val html = xhtml(
            """<span id="chapter3"></span><p><span id="chapter3_verse14"></span><strong><sup>14</sup></strong>Deus disse: “Eu Sou o que Sou”.</p>"""
        )
        val verses = NwtEpubParser.extractFromXhtml(html)!!
        assertTrue(verses.single().text.contains("Eu Sou o que Sou"))
    }
}
