package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coletor único: título, texto, esboço, seções e subpontos. */
class NoteRefsCollectorTest {

    private fun section(
        title: String = "Tópico",
        contentHtml: String = "",
        bibleRefs: List<String> = emptyList(),
        publicationRefs: List<PublicationRef> = emptyList(),
    ) = SpeechSection(
        id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
        title = title, minutes = 5, contentHtml = contentHtml,
        bibleRefs = bibleRefs, publicationRefs = publicationRefs,
        methodPrinciple = null, createdAt = 0, updatedAt = 0,
    )

    private fun subPoint(
        outlineText: String = "",
        developedHtml: String = "",
        bibleRefs: List<String> = emptyList(),
        publicationRefs: List<PublicationRef> = emptyList(),
    ) = SubPoint(
        id = "sp1", sectionId = "s1", order = 0,
        outlineText = outlineText, bibleRefs = bibleRefs,
        publicationRefs = publicationRefs, instruction = null,
        developedHtml = developedHtml, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun coletaTituloTextoEsbocoSecoesESubpontos() {
        val refs = NoteRefsCollector.collect(
            noteTitle = "Ter esperança (w25.07 20 § 2)",
            mdText = "Baruque (Jer. 45:3)",
            plainText = "lmd lição 7 ponto 4",
            outlineRefsJson = RefDetector.detectedToJson(
                RefDetector.detect("th lição 7 ponto 4"),
            ),
            sections = listOf(
                section(
                    title = "lmd apêndice A ponto 20",
                    contentHtml = "<p>Veja <b>Gên 3:6</b>.</p>",
                    bibleRefs = listOf("Rom 5:12"),
                    publicationRefs = listOf(
                        PublicationRef("w25.07", paragraph = 8),
                    ),
                ),
            ).map { it.toSectionRefs() },
            subPoints = listOf(
                subPoint(
                    outlineText = "Leia Mateus 7:13",
                    developedHtml = "<p>Compare com lmd lição 7 ponto 4.</p>",
                    bibleRefs = listOf("João 3:16"),
                    publicationRefs = listOf(
                        PublicationRef("th", chapter = "lição 7", paragraph = 4),
                    ),
                ),
            ).map { it.toSubPointRefs() },
        )

        assertTrue(refs.publications.any { it.raw == "w25.07 20 § 2" })
        assertTrue(refs.publications.any { it.raw == "lmd lição 7 ponto 4" })
        assertTrue(refs.publications.any { it.raw == "th lição 7 ponto 4" })
        assertTrue(refs.publications.any { it.raw == "lmd apêndice A ponto 20" })
        assertTrue(refs.publications.any { it.raw.contains("w25.07") && it.paragraph == 8 })
        assertTrue(refs.bibles.any { it.label == "Jeremias" && it.chapter == 45 && it.verse == 3 })
        assertTrue(refs.bibles.any { it.label == "Gênesis" && it.chapter == 3 && it.verse == 6 })
        assertTrue(refs.bibles.any { it.label == "Romanos" && it.chapter == 5 && it.verse == 12 })
        assertTrue(refs.bibles.any { it.label == "Mateus" && it.chapter == 7 && it.verse == 13 })
        assertTrue(refs.bibles.any { it.label == "João" && it.chapter == 3 && it.verse == 16 })
    }

    @Test
    fun dedupPublicacaoPorEdicaoUnidadePaginaEParagrafo() {
        val refs = NoteRefsCollector.collect(
            mdText = "w25.07 22 §§ 8-9 e w25.07 20 § 2",
            sections = listOf(
                section(
                    title = "w25.07 22 §§ 8-9",
                    publicationRefs = listOf(
                        PublicationRef("w25.07", page = 22, paragraph = 8, paragraphEnd = 9),
                    ),
                ),
            ).map { it.toSectionRefs() },
        )
        // A mesma citação (raw/título/JSON) vira uma; § 2 é outra.
        assertEquals(2, refs.publications.size)
        assertEquals(
            1,
            refs.publications.count { it.paragraph == 8 && it.paragraphEnd == 9 },
        )
    }

    @Test
    fun dedupBibliaPorLivroCanonico() {
        val refs = NoteRefsCollector.collect(
            mdText = "Jer. 45:3 e Jeremias 45:3",
            sections = listOf(section(bibleRefs = listOf("Jeremias 45:3"))).map { it.toSectionRefs() },
        )
        assertEquals(1, refs.bibles.size)
        assertEquals("Jeremias", refs.bibles.single().label)
        assertEquals(45, refs.bibles.single().chapter)
        assertEquals(3, refs.bibles.single().verse)
    }

    @Test
    fun htmlNaoQuebraRefsNoConteudoNemNoDesenvolvimento() {
        val refs = NoteRefsCollector.collect(
            sections = listOf(
                section(contentHtml = "<p>Veja <b>Jer. 45:3</b> e <i>lmd lição 7 ponto 4</i>.</p>"),
            ).map { it.toSectionRefs() },
            subPoints = listOf(
                subPoint(developedHtml = "<div><span>Gên</span> <span>3:6</span></div>"),
            ).map { it.toSubPointRefs() },
        )
        assertTrue(refs.publications.any { it.raw == "lmd lição 7 ponto 4" })
        assertTrue(refs.bibles.any { it.label == "Jeremias" && it.chapter == 45 })
        assertTrue(refs.bibles.any { it.label == "Gênesis" && it.chapter == 3 })
    }

    @Test
    fun entidadesHtmlNaoEscondemRefs() {
        val refs = NoteRefsCollector.collect(
            sections = listOf(
                section(
                    contentHtml = "<p>Jo&atilde;o 14&colon;28&semi; " +
                        "1 Cor&period; 11&colon;3</p>",
                ),
            ).map { it.toSectionRefs() },
        )
        assertTrue(refs.bibles.any { it.label == "João" && it.chapter == 14 && it.verse == 28 })
        assertTrue(
            refs.bibles.any {
                it.label == "1 Coríntios" && it.chapter == 11 && it.verse == 3
            },
        )
    }

    @Test
    fun semFontes_retornaVazio() {
        val refs = NoteRefsCollector.collect(noteTitle = "Discurso sem citações")
        assertTrue(refs.isEmpty)
        assertEquals(NoteRefs.EMPTY, refs)
    }

    @Test
    fun checklistEPromptPartemDaMesmaColetaUnificada() {
        val collected = NoteRefsCollector.collect(
            noteTitle = "w25.07 no título",
            mdText = "Gên 3:6 no texto",
            outlineRefsJson = RefDetector.detectedToJson(
                RefDetector.detect("lmd lição 7 ponto 4"),
            ),
            sections = listOf(
                section(
                    contentHtml = "<p>w24.01 § 3</p>",
                    bibleRefs = listOf("Jer. 45:3"),
                ),
            ).map { it.toSectionRefs() },
            subPoints = listOf(
                subPoint(developedHtml = "<p>w25.07 22 §§ 8-9</p>"),
            ).map { it.toSubPointRefs() },
        )

        val candidates = NoteSourcesResolver.candidatesOf(collected)
        assertEquals(
            collected.publications.size + collected.bibles.size,
            candidates.map { it.key }.distinct().size,
        )
        assertTrue(candidates.any { it.ref.contains("w25.07") })
        assertTrue(candidates.any { it.ref.contains("lmd") })
        assertTrue(candidates.any { it.ref.contains("w24.01") })
        assertTrue(candidates.any { it.ref == "Jeremias 45:3" })
        assertTrue(candidates.any { it.ref == "Gênesis 3:6" })
    }

    @Test
    fun publicationRefComArtigoECapituloSobreviveAoRoundTripDoCodec() {
        val items = listOf(
            PublicationRef("lmd", chapter = "lição 7", paragraph = 4),
            PublicationRef("it", article = "Gedalias", paragraph = 3),
        )
        val decoded = com.bettertalker.app.data.db.decodePublicationRefList(
            com.bettertalker.app.data.db.encodePublicationRefList(items),
        )
        assertEquals(items, decoded)
    }
}
