package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bloco de agrupamento do sheet — publicação primária + livros na TNM. */
class GroupNoteSourcesTest {

    private fun publication(
        ref: String,
        publicationKey: String?,
        publicationLabel: String?,
        status: SourceStatus,
    ) = NoteSource(
        ref = ref,
        kind = SourceKind.PUBLICATION,
        citation = CitationStatus(editionStatusOf(status), defaultTextStatus(status)),
        publicationKey = publicationKey,
        publicationLabel = publicationLabel,
    )

    private fun bible(
        ref: String,
        book: String?,
        chapter: Int?,
        verse: Int?,
        status: SourceStatus,
    ) = NoteSource(
        ref = ref,
        kind = SourceKind.BIBLE,
        citation = CitationStatus(editionStatusOf(status), defaultTextStatus(status)),
        book = book,
        chapter = chapter,
        verse = verse,
        publicationKey = "nwt",
        publicationLabel = "TNM",
    )

    @Test
    fun agrupaPublicacaoEBibliaNaTnmComSubgruposDeLivro() {
        val sources = listOf(
            publication("w25.07 22 §§ 8-9", "w25.07", "A Sentinela — julho de 2025", SourceStatus.EDITION_MISSING),
            publication("w25.07 20 § 2", "w25.07", "A Sentinela — julho de 2025", SourceStatus.EDITION_MISSING),
            publication("lmd apêndice A ponto 20", "lmd", "Ame as Pessoas", SourceStatus.RESOLVED),
            bible("Jeremias 45:5", "Jeremias", 45, 5, SourceStatus.EDITION_MISSING),
            bible("Jeremias 45:3", "Jeremias", 45, 3, SourceStatus.EDITION_MISSING),
            bible("Gênesis 1:1", "Gênesis", 1, 1, SourceStatus.RESOLVED),
        )

        val groups = groupNoteSources(sources)

        // Específica faltante primeiro, TNM depois, específica OK por último.
        assertEquals(
            listOf("pub:w25.07", "pub:nwt", "pub:lmd"),
            groups.map { it.key },
        )
        assertEquals("A Sentinela — julho de 2025", groups[0].label)
        assertEquals(GroupStatus.MISSING, groups[0].status)
        assertEquals(2, groups[0].total)

        val nwt = groups[1]
        assertEquals("TNM", nwt.label)
        assertEquals(3, nwt.total)
        assertEquals(GroupStatus.PARTIAL, nwt.status)
        assertEquals(listOf("Gênesis", "Jeremias"), nwt.bookGroups.map { it.label })
        // Dentro do livro: ordem por capítulo/versículo.
        assertEquals(
            listOf("Jeremias 45:3", "Jeremias 45:5"),
            nwt.bookGroups[1].sources.map { it.ref },
        )
        assertEquals(GroupStatus.OK, nwt.bookGroups[0].status)
        assertEquals(GroupStatus.MISSING, nwt.bookGroups[1].status)

        assertEquals("Ame as Pessoas", groups[2].label)
        assertEquals(GroupStatus.OK, groups[2].status)
    }

    @Test
    fun statusAgregadoPorPublicacaoELivro() {
        val groups = groupNoteSources(
            listOf(
                bible("Jeremias 45:3", "Jeremias", 45, 3, SourceStatus.RESOLVED),
                bible("Jeremias 45:5", "Jeremias", 45, 5, SourceStatus.EDITION_MISSING),
            ),
        )
        val nwt = groups.single()
        assertEquals(GroupStatus.PARTIAL, nwt.status)
        assertEquals(1, nwt.resolved)
        assertEquals(2, nwt.total)
        assertEquals(GroupStatus.PARTIAL, nwt.bookGroups.single().status)
    }

    @Test
    fun fallback_semBookOuPublicationKey() {
        val groups = groupNoteSources(
            listOf(
                publication("ref solta", null, null, SourceStatus.EDITION_MISSING),
                bible("Versículo solto", null, null, null, SourceStatus.RESOLVED),
            ),
        )
        assertEquals("Outras publicações", groups[0].label)
        assertEquals("TNM", groups[1].label)
        assertEquals("Outras referências bíblicas", groups[1].bookGroups.single().label)
        assertEquals(SourceKind.PUBLICATION, groups[0].kind)
        assertEquals(SourceKind.BIBLE, groups[1].kind)
    }

    @Test
    fun naoDuplicaGrupoDaMesmaPublicacao() {
        val groups = groupNoteSources(
            listOf(
                publication("w25.07 22 §§ 8-9", "w25.07", "A Sentinela — julho de 2025", SourceStatus.RESOLVED),
                publication("w25.07 20 § 2", "w25.07", "A Sentinela — julho de 2025", SourceStatus.RESOLVED),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().total)
    }

    @Test
    fun grupoComFontesTransitorias_mostraAndamento() {
        val groups = groupNoteSources(
            listOf(
                publication("w25.07 22 §§ 8-9", "w25.07", "A Sentinela — julho de 2025", SourceStatus.DOWNLOADING),
                publication("w25.07 20 § 2", "w25.07", "A Sentinela — julho de 2025", SourceStatus.DOWNLOADING),
            ),
        )
        val group = groups.single()
        assertEquals(2, group.pendingCount)
        assertTrue(group.allPending)
        assertEquals(0, group.resolved)
        assertEquals(GroupStatus.MISSING, group.status)
    }

    @Test
    fun listaVazia_naoTemGrupos() {
        assertTrue(groupNoteSources(emptyList()).isEmpty())
    }

    @Test
    fun metadadosDePublicacao_formatamChaveERotulo() {
        val w = RefDetector.detect("w25.07 22 §§ 8-9").single()
        assertEquals("w25.07", publicationDisplayKey(w))
        assertEquals("A Sentinela — julho de 2025", publicationEditionLabel(w))

        val lmd = RefDetector.detect("lmd apêndice A ponto 20").single()
        assertEquals("lmd", publicationDisplayKey(lmd))
        assertEquals("Ame as Pessoas", publicationEditionLabel(lmd))

        val th = RefDetector.detect("th lição 7 ponto 4").single()
        assertEquals("th", publicationDisplayKey(th))
        assertEquals("Melhore Sua Leitura e Seu Ensino", publicationEditionLabel(th))
    }

    @Test
    fun ordemCanonicaDosLivros() {
        assertEquals(1, bibleBookOrder("Gênesis"))
        assertEquals(24, bibleBookOrder("Jeremias"))
        assertEquals(Int.MAX_VALUE, bibleBookOrder("Livro desconhecido"))
    }
}
