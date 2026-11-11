package com.bettertalker.app.data.publication

import com.bettertalker.app.data.db.PassageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationUnitBuilderTest {

    private fun p(
        ord: Int,
        section: String,
        page: Int? = null,
        attachmentId: String = "att",
    ) = PassageEntity(
        id = "$attachmentId-p$ord",
        attachmentId = attachmentId,
        text = "texto $ord",
        normalized = "texto $ord",
        section = section,
        page = page,
        ord = ord,
    )

    private fun build(
        passages: List<PassageEntity>,
        symbol: String = "w17.10",
        pubKind: String? = "magazine",
    ) = PublicationUnitBuilder.build("att", symbol, pubKind, passages)

    @Test
    fun revistaSemCabecalhoExplicitoViraArtigo() {
        val units = build(
            listOf(
                p(0, "Primeiro artigo", 10),
                p(1, "Primeiro artigo", 11),
                p(2, "Segundo artigo", 12),
                p(3, "Segundo artigo", 13),
            )
        )
        assertEquals(2, units.size)
        assertEquals(listOf("ARTICLE", "ARTICLE"), units.map { it.kind })
        assertEquals(listOf(0, 2), units.map { it.startOrd })
        assertEquals(listOf(1, 3), units.map { it.endOrd })
        assertEquals(10, units[0].startPage)
        assertEquals(11, units[0].endPage)
        assertEquals(12, units[1].startPage)
        assertEquals(13, units[1].endPage)
        assertTrue(units.all { it.parentId == null })
        assertEquals(2, units.map { it.canonicalKey }.toSet().size)
    }

    @Test
    fun livroComParteCapituloEApendiceMantemHierarquia() {
        val units = build(
            listOf(
                p(0, "PARTE 1"),
                p(1, "Texto de abertura"),
                p(2, "Texto de abertura"),
                p(3, "CAPÍTULO 1"),
                p(4, "CAPÍTULO 1"),
                p(5, "Subtítulo"),
                p(6, "Subtítulo"),
                p(7, "Subtítulo"),
                p(8, "CAPÍTULO 2"),
                p(9, "CAPÍTULO 2"),
                p(10, "CAPÍTULO 2"),
                p(11, "PARTE 2"),
                p(12, "CAPÍTULO 3"),
                p(13, "CAPÍTULO 3"),
                p(14, "CAPÍTULO 3"),
                p(15, "APÊNDICE A"),
                p(16, "APÊNDICE A"),
                p(17, "APÊNDICE A"),
            ),
            symbol = "jr",
            pubKind = "book",
        )

        assertEquals(
            listOf("PART", "CHAPTER", "CHAPTER", "PART", "CHAPTER", "APPENDIX"),
            units.map { it.kind },
        )
        assertEquals(listOf("1", "1", "2", "2", "3", "A"), units.map { it.number })
        assertEquals(listOf(0, 3, 8, 11, 12, 15), units.map { it.startOrd })
        assertEquals(listOf(2, 7, 10, 11, 14, 17), units.map { it.endOrd })

        assertNull(units[0].parentId)
        assertEquals(units[0].id, units[1].parentId)
        assertEquals(units[0].id, units[2].parentId)
        assertNull(units[3].parentId)
        assertEquals(units[3].id, units[4].parentId)
        assertNull(units[5].parentId)
    }

    @Test
    fun licaoUsaKindLesson() {
        val units = build(
            listOf(
                p(0, "Lição 1"),
                p(1, "Lição 1"),
                p(2, "Lição 2"),
                p(3, "Lição 2"),
            ),
            symbol = "lmd",
            pubKind = "jwpub",
        )
        assertEquals(listOf("LESSON", "LESSON"), units.map { it.kind })
        assertEquals(listOf("1", "2"), units.map { it.number })
        assertEquals(listOf(0, 2), units.map { it.startOrd })
        assertEquals(listOf(1, 3), units.map { it.endOrd })
    }

    @Test
    fun itSemPrefixoUsaArtigo() {
        val units = build(
            listOf(
                p(0, "Verbete A"),
                p(1, "Verbete A"),
                p(2, "Verbete B"),
            ),
            symbol = "it",
            pubKind = "book",
        )
        assertEquals(listOf("ARTICLE", "ARTICLE"), units.map { it.kind })
        assertEquals(listOf(0, 2), units.map { it.startOrd })
    }

    @Test
    fun titulosRepetidosNaoSeMisturam() {
        val units = build(
            listOf(
                p(0, "Capítulo 1"),
                p(1, "Subtítulo A"),
                p(2, "Subtítulo A"),
                p(3, "Capítulo 1"),
                p(4, "Capítulo 1"),
            ),
            symbol = "jr",
            pubKind = "book",
        )
        assertEquals(listOf("CHAPTER", "CHAPTER"), units.map { it.kind })
        assertEquals(listOf("1", "1"), units.map { it.number })
        assertEquals(listOf(0, 3), units.map { it.startOrd })
        assertEquals(listOf(2, 4), units.map { it.endOrd })
        assertNotEquals(units[0].canonicalKey, units[1].canonicalKey)
    }

    @Test
    fun semNenhumHeadingViraDocumentoUnico() {
        val units = build(
            listOf(
                p(0, ""),
                p(1, ""),
                p(2, ""),
            ),
            symbol = "pdf",
            pubKind = "book",
        )
        assertEquals(1, units.size)
        assertEquals("DOCUMENT", units.single().kind)
        assertEquals(0, units.single().startOrd)
        assertEquals(2, units.single().endOrd)
        assertEquals("Documento", units.single().title)
    }

    @Test
    fun nwtFicaForaDoIndice() {
        assertTrue(
            build(
                listOf(p(0, "Capítulo 1")),
                symbol = "nwt",
                pubKind = "bible",
            ).isEmpty()
        )
    }

    @Test
    fun epubEPdfViramDocumentoMesmoComSecoes() {
        for (kind in listOf("epub", "pdf")) {
            val units = build(
                listOf(
                    p(0, "Capítulo 1"),
                    p(1, "Capítulo 1"),
                    p(2, "Capítulo 2"),
                ),
                symbol = "livro",
                pubKind = kind,
            )
            assertEquals(kind, 1, units.size)
            assertEquals(kind, "DOCUMENT", units.single().kind)
            assertEquals(kind, 0, units.single().startOrd)
            assertEquals(kind, 2, units.single().endOrd)
        }
    }

    @Test
    fun passagesSaoOrdenadasPorOrd() {
        val units = build(
            listOf(
                p(2, "Capítulo 2"),
                p(0, "Capítulo 1"),
                p(1, "Capítulo 1"),
            ),
            symbol = "jr",
            pubKind = "book",
        )
        assertEquals(listOf("CHAPTER", "CHAPTER"), units.map { it.kind })
        assertEquals(listOf(0, 2), units.map { it.startOrd })
        assertEquals(listOf(1, 2), units.map { it.endOrd })
    }

    @Test
    fun jwpubDeLivroSemCapituloExplicitoUsaChapter() {
        val units = build(
            listOf(
                p(0, "1. Título"),
                p(1, "1. Título"),
                p(2, "2. Outro título"),
            ),
            symbol = "be",
            pubKind = "jwpub",
        )
        assertEquals(listOf("CHAPTER", "CHAPTER"), units.map { it.kind })
    }

    @Test
    fun jwpubDeLivroClSemHeadingExplicitoUsaChapter() {
        val units = build(
            listOf(
                p(0, "Introdução"),
                p(1, "Introdução"),
                p(2, "Parte prática"),
            ),
            symbol = "cl",
            pubKind = "jwpub",
        )
        assertEquals(listOf("CHAPTER", "CHAPTER"), units.map { it.kind })
    }

    @Test
    fun jwpubDeRevistaSemHeadingExplicitoUsaArticle() {
        val units = build(
            listOf(
                p(0, "Primeiro artigo"),
                p(1, "Primeiro artigo"),
                p(2, "Segundo artigo"),
            ),
            symbol = "wp17.09",
            pubKind = "jwpub",
        )
        assertEquals(listOf("ARTICLE", "ARTICLE"), units.map { it.kind })
    }

    @Test
    fun fallbackJwpubPreservaTiposEspeciais() {
        fun kindOf(symbol: String) = build(
            listOf(p(0, "Título genérico"), p(1, "Título genérico")),
            symbol = symbol,
            pubKind = "jwpub",
        ).single().kind

        assertEquals("LESSON", kindOf("lmd"))
        assertEquals("LESSON", kindOf("th"))
        assertEquals("ARTICLE", kindOf("it"))
        assertEquals("ARTICLE", kindOf("rsg"))
        assertEquals("SECTION", kindOf("s38"))
    }

    @Test
    fun paginasUsamPrimeiroEUltimoPageDaUnidade() {
        val units = build(
            listOf(
                p(0, "Capítulo 1", 7),
                p(1, "Subtítulo", 8),
                p(2, "Subtítulo", 9),
            ),
            symbol = "jr",
            pubKind = "book",
        )
        assertEquals(7, units.single().startPage)
        assertEquals(9, units.single().endPage)
    }
}
