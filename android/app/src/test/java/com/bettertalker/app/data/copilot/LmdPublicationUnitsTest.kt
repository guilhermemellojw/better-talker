package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.NoteSourcesResolver
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SectionRefs
import com.bettertalker.app.data.util.groupNoteSources
import com.bettertalker.app.domain.planning.PublicationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Referências são publicação + unidade. Duas unidades da MESMA publicação
 * (lmd) precisam sobreviver ao dedup e aparecer no inventário/checklist.
 *
 * Caso real: “... apêndice A da brochura Ame as Pessoas. (lmd lição 9 ponto 5)”.
 */
class LmdPublicationUnitsTest {

    private val example =
        "Cultivando o interesse (4 min) TESTEMUNHO INFORMAL. Fale sobre uma das " +
            "verdades do apêndice A da brochura Ame as Pessoas. (lmd lição 9 ponto 5)"

    @Test
    fun detectorReconheceAsDuasUnidadesDaMesmaPublicacao() {
        val refs = RefDetector.detect(example)
        refs.forEach {
            println(
                "raw=${it.raw} pub=${it.pubKey} article=${it.article} " +
                    "chapter=${it.chapter} page=${it.page} para=${it.paragraph} " +
                    "end=${it.paragraphEnd}"
            )
        }
        assertEquals("duas unidades lmd", 2, refs.size)
        assertTrue(refs.all { it.pubKey == "lmd" })
        val keys = refs.map { NoteRefsCollector.sourceKey(it) }.toSet()
        assertEquals("unidades diferentes", 2, keys.size)
        assertTrue(
            "apêndice A",
            refs.any {
                (it.article.orEmpty() + " " + it.chapter.orEmpty())
                    .contains("apêndice a", ignoreCase = true)
            },
        )
        assertTrue(
            "lição 9 ponto 5",
            refs.any {
                it.chapter.orEmpty().contains("lição 9", ignoreCase = true) &&
                    it.paragraph == 5
            },
        )
    }

    @Test
    fun coletorMantemDuasUnidadesSemGenericaDuplicada() {
        val refs = NoteRefsCollector.collect(mdText = example)
        refs.publications.forEach {
            println("collect raw=${it.raw} chapter=${it.chapter} para=${it.paragraph}")
        }
        assertEquals("duas publicações/unidades", 2, refs.publications.size)
        assertEquals(
            "sem book|lmd genérica",
            0,
            refs.publications.count {
                NoteRefsCollector.sourceKey(it) == "book|lmd||||-1|-1|-1"
            },
        )
    }

    @Test
    fun checklistAgrupaPorPublicacaoEListaAsDuasUnidades() {
        val refs = NoteRefsCollector.collect(mdText = example)
        val candidates = NoteSourcesResolver.candidatesOf(refs)
        val statuses = candidates.associate { c ->
            c.key to RefDetector.RefStatus(
                ref = c.publication!!,
                resolved = true,
                contentResolved = true,
            )
        }
        val sources = NoteSourcesResolver.toNoteSources(candidates, statuses, emptyMap())
        val groups = groupNoteSources(sources.sources)
        assertEquals("um grupo de publicação", 1, groups.size)
        assertEquals("lmd", groups.first().sources.first().publicationKey)
        assertEquals("duas unidades", 2, groups.first().sources.size)
        assertTrue(
            "as duas unidades aparecem",
            groups.first().sources.any { it.ref.contains("lição 9", true) } &&
                groups.first().sources.any { it.ref.contains("apêndice", true) },
        )
    }

    @Test
    fun genericaSemCapituloNaoDuplicaUnidadeEspecifica() {
        // Caso real da nota “Cultivando…”: o título tem “lmd lição 7 ponto 4”
        // e a seção persistiu publicationRefsJson [{"symbol":"lmd","paragraph":4}].
        val title =
            "Fale obre as verdades do apêndice A · Testemunho INFORMAL · lmd lição 7 ponto 4"
        val refs = NoteRefsCollector.collect(
            noteTitle = "Cultivando o interesse (4 min)",
            sections = listOf(
                SectionRefs(
                    title = title,
                    publicationRefs = listOf(PublicationRef(symbol = "lmd", paragraph = 4)),
                ),
            ),
        )
        refs.publications.forEach {
            println("ref=${it.raw} chapter=${it.chapter} para=${it.paragraph}")
        }
        assertEquals("uma unidade específica", 1, refs.publications.size)
        assertEquals("lição 7", refs.publications.single().chapter)
        assertEquals(4, refs.publications.single().paragraph)
    }


}
