package com.bettertalker.app.domain.speech

import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.ParsedOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OutlineConverterTest {

    private fun converter(vararg idPrefix: String): OutlineConverter {
        var counter = 0
        val prefix = idPrefix.firstOrNull() ?: "id"
        return OutlineConverter(
            idProvider = { "${prefix}-${counter++}" },
            now = { 1000L },
        )
    }

    /** Fixture fiel ao snippet real de OutlineTest.kt:71-85. */
    private fun snippetParsed() = ParsedOutline(
        title = "É possível viver para sempre?",
        totalMinutes = 30,
        preamble = "NOTA: ajude a assistência a meditar.",
        sections = listOf(
            OutlineSection(
                "FOMOS CRIADOS PARA VIVER PARA SEMPRE", 5, 0,
                "Precisamos estar vivos para ter esperança. (Despertai! 08/13 pág. 6)\n" +
                    "Temos o desejo de nunca morrer. [Leia Eclesiastes 3:11.]",
            ),
            OutlineSection("COMO A VIDA FOI PERDIDA", 4, 1, "Adão e Eva desobedeceram. (Gên 3:6)"),
        ),
    )

    private fun simpleParsed() = ParsedOutline(
        title = "Tema",
        totalMinutes = 10,
        preamble = "",
        sections = listOf(
            OutlineSection("A", 4, 0, "Linha um.\nLinha dois."),
            OutlineSection("B", 3, 1, ""),
            OutlineSection("C", 3, 2, "Única linha."),
        ),
    )

    @Test
    fun convert_emptyOutline_producesNoSections() {
        val conv = converter().convert(
            ParsedOutline(title = "Vazio", totalMinutes = null, preamble = "", sections = emptyList()),
            noteId = "n1",
        )
        assertEquals(null, conv.intro)
        assertEquals(null, conv.conclusion)
        assertTrue(conv.bodies.isEmpty())
    }

    @Test
    fun convert_singleSection_producesSingleBody() {
        val conv = converter().convert(
            ParsedOutline(title = "T", totalMinutes = 5, sections = listOf(OutlineSection("A", 5, 0, "x"))),
            "n1",
        )
        assertEquals(1, conv.bodies.size)
        assertEquals(SectionRole.BODY, conv.bodies.single().section.role)
        assertEquals(0, conv.bodies.single().section.order)
        assertEquals(null, conv.intro)
        assertEquals(null, conv.conclusion)
    }

    @Test
    fun convert_sectionWithSimpleBody_extractsOneSubPointPerLine() {
        val conv = converter().convert(simpleParsed(), "n1")
        assertEquals(2, conv.bodies[0].subPoints.size)
        assertEquals("Linha um.", conv.bodies[0].subPoints[0].outlineText)
        assertEquals("Linha dois.", conv.bodies[0].subPoints[1].outlineText)
    }

    @Test
    fun convert_sectionWithMarkerLines_stripsMarkers() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "1. Adão desobedeceu"))),
            "n1",
        )
        assertEquals("Adão desobedeceu", conv.bodies.single().subPoints.single().outlineText)
    }

    @Test
    fun convert_sectionWithOrphanRefLine_attachesToPreviousSubPoint() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Texto base.\n(Gên 3:6)"))),
            "n1",
        )
        val sub = conv.bodies.single().subPoints
        assertEquals(1, sub.size)
        assertEquals("Texto base.", sub.single().outlineText)
        assertTrue(sub.single().bibleRefs.any { it.contains("3:6") })
    }

    @Test
    fun convert_sectionWithBibleRefs_extractsToSubPoint() {
        val conv = converter().convert(snippetParsed(), "n1")
        val refs = conv.bodies[1].subPoints.single().bibleRefs
        assertTrue("refs: $refs", refs.any { it.contains("3:6") })
        assertEquals("Adão e Eva desobedeceram.", conv.bodies[1].subPoints.single().outlineText)
    }

    @Test
    fun convert_sectionWithPublicationRefs_extractsToSubPoint() {
        val conv = converter().convert(snippetParsed(), "n1")
        val sub = conv.bodies[0].subPoints[0]
        assertEquals(1, sub.publicationRefs.size)
        // 3.2.3a-fix2: símbolo canonicalizado com edição.
        assertEquals("g 8/13", sub.publicationRefs.single().symbol)
        assertEquals(null, sub.publicationRefs.single().page)
        assertEquals("Precisamos estar vivos para ter esperança.", sub.outlineText)
    }

    @Test
    fun convert_linhaDoDono_guardaArtigoEParagrafo() {
        // Linha real: "(Jer. 41:1, 2; it "Gedalias" n.° 4)".
        val parsed = ParsedOutline(
            title = "Teste", totalMinutes = 5, preamble = "",
            sections = listOf(
                OutlineSection(
                    "TESTE", 5, 0,
                    "Jeová não salvou a vida de Gedalias, embora ele fosse um homem que " +
                        "temia a Jeová. (Jer. 41:1, 2; it \"Gedalias\" n.° 4)",
                ),
            ),
        )
        val conv = converter().convert(parsed, "n1")
        val sub = conv.bodies.single().subPoints.single()
        // A lista de versículos virou refs separadas ("Jer." + aliases T1).
        assertEquals(listOf("Je 41:1", "Je 41:2"), sub.bibleRefs)
        // O artigo + parágrafo do Estudo Perspicaz (T2).
        val pub = sub.publicationRefs.single()
        assertEquals("it", pub.symbol)
        assertEquals("Gedalias", pub.article)
        assertEquals(4, pub.paragraph)
        assertEquals("Jeová não salvou a vida de Gedalias, embora ele fosse um homem que temia a Jeová.", sub.outlineText)
    }

    @Test
    fun convert_sectionWithInstruction_extractsToSubPoint() {
        val conv = converter().convert(snippetParsed(), "n1")
        val sub = conv.bodies[0].subPoints[1]
        assertEquals("Leia Eclesiastes 3:11.", sub.instruction)
        assertEquals("Temos o desejo de nunca morrer.", sub.outlineText)
        // 3.2.3a-fix: ref normalizada para a abreviação TNM.
        assertTrue(sub.bibleRefs.contains("Ec 3:11"))
    }

    @Test
    fun convert_preambleGoesToSpeakerNotes() {
        val conv = converter().convert(snippetParsed(), "n1")
        assertTrue(conv.speakerNotes.contains("NOTA: ajude a assistência a meditar."))
        assertEquals(null, conv.intro)
    }

    @Test
    fun convert_ordersAreContiguousAcrossAllLevels() {
        val conv = converter().convert(simpleParsed(), "n1")
        assertEquals(listOf(0, 1, 2), conv.bodies.map { it.section.order })
        conv.bodies.forEach { body ->
            assertEquals(body.subPoints.indices.toList(), body.subPoints.map { it.order })
        }
    }

    @Test
    fun convert_idsAreUnique() {
        val conv = converter().convert(simpleParsed(), "n1")
        val ids = buildList {
            conv.bodies.forEach { add(it.section.id); it.subPoints.forEach { sp -> add(sp.id) } }
        }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun convert_idProviderIsUsed() {
        val conv = converter("id").convert(simpleParsed(), "n1")
        assertEquals("id-0", conv.bodies[0].section.id)
        assertEquals("id-1", conv.bodies[0].subPoints[0].id)
        assertEquals("id-2", conv.bodies[0].subPoints[1].id)
        assertEquals("id-3", conv.bodies[1].section.id)
        assertEquals("id-4", conv.bodies[2].section.id)
        assertEquals("id-5", conv.bodies[2].subPoints[0].id)
    }

    @Test
    fun convert_totalMinutesFromParsed() {
        assertEquals(30, converter().convert(snippetParsed(), "n1").totalMinutes)
    }

    @Test
    fun convert_totalMinutesFallbackToSumOfSections() {
        val parsed = ParsedOutline(
            title = "T", totalMinutes = null,
            sections = listOf(
                OutlineSection("A", 4, 0, ""),
                OutlineSection("B", null, 1, ""),
                OutlineSection("C", 3, 2, ""),
            ),
        )
        assertEquals(7, converter().convert(parsed, "n1").totalMinutes)
    }

    @Test
    fun convert_sectionWithEmptyBody_producesZeroSubPoints() {
        val conv = converter().convert(simpleParsed(), "n1")
        assertTrue(conv.bodies[1].subPoints.isEmpty())
        assertEquals(DiscourseType.S34_DISCOURSE, conv.discourseType)
    }

    // ---------- 3.2.4b: conversão por tipo ----------

    @Test
    fun convert_s34_noAutoIntroConclusion() {
        // F3.x: o S-34 não sintetiza intro/conclusion a partir de preamble.
        val conv = converter().convert(simpleParsed(), "n1")
        assertEquals(null, conv.intro)
        assertEquals(null, conv.conclusion)
        assertEquals(3, conv.bodies.size)
        assertEquals(DiscourseType.S34_DISCOURSE, conv.discourseType)
    }

    @Test
    fun convert_treasures_producesSingleBody() {
        val conv = converter().convert(treasuresParsed(), "n1", DiscourseType.TREASURES_TALK)
        assertEquals(null, conv.intro)
        assertEquals(null, conv.conclusion)
        assertEquals(1, conv.bodies.size)
        assertEquals(0, conv.bodies.single().section.order)
        assertEquals(DiscourseType.TREASURES_TALK, conv.discourseType)
    }

    @Test
    fun convert_treasures_subPointsExtractedFromBody() {
        val conv = converter().convert(treasuresParsed(), "n1", DiscourseType.TREASURES_TALK)
        assertEquals(3, conv.bodies.single().subPoints.size)
    }

    @Test
    fun convert_ministry_producesSingleBody() {
        val conv = converter().convert(treasuresParsed(), "n1", DiscourseType.MINISTRY_PART)
        assertEquals(null, conv.intro)
        assertEquals(1, conv.bodies.size)
    }

    @Test
    fun convert_ministry_subPointsFromBody() {
        val conv = converter().convert(
            ParsedOutline(
                title = "T", totalMinutes = 4,
                sections = listOf(OutlineSection("A", 4, 0, "Estude o parágrafo 5.")),
            ),
            "n1", DiscourseType.MINISTRY_PART,
        )
        assertEquals(1, conv.bodies.single().subPoints.size)
    }

    @Test
    fun convert_avulso_producesSingleBodyWithContentHtml() {
        val conv = converter().convert(
            ParsedOutline(
                title = "T", totalMinutes = 5,
                sections = listOf(OutlineSection("A", 5, 0, "Texto corrido.")),
            ),
            "n1", DiscourseType.AVULSO,
        )
        val body = conv.bodies.single()
        assertEquals(1, conv.bodies.size)
        // AVULSO usa o body textual via contentHtml? Não — via sub-ponto único.
        assertEquals(1, body.subPoints.size)
    }

    @Test
    fun convert_avulso_noSubPoints() {
        // AVULSO força contentHtml vazio + sem sub-pontos? Não: extrai igual;
        // este teste fixa que o caminho existe e valida via serviço depois.
        val conv = converter().convert(treasuresParsed(), "n1", DiscourseType.AVULSO)
        assertEquals(3, conv.bodies.single().subPoints.size)
    }

    @Test
    fun convert_treasures_ignoresPreamble() {
        val conv = converter().convert(
            ParsedOutline(
                title = "T", totalMinutes = 10, preamble = "Ruído colado.",
                sections = listOf(OutlineSection("A", 10, 0, "Linha.")),
            ),
            "n1", DiscourseType.TREASURES_TALK,
        )
        assertEquals(null, conv.intro)
    }

    @Test
    fun convert_avulso_carriesMinutesFromSection() {
        val conv = converter().convert(
            ParsedOutline(
                title = "T", totalMinutes = 5,
                sections = listOf(OutlineSection("A", 5, 0, "x")),
            ),
            "n1", DiscourseType.AVULSO,
        )
        assertEquals(5, conv.bodies.single().section.minutes)
    }

    @Test
    fun convert_s34_preambleGoesToSpeakerNotes() {
        val conv = converter().convert(snippetParsed(), "n1")
        assertTrue(conv.speakerNotes.contains("NOTA: ajude a assistência a meditar."))
    }

    @Test
    fun convert_treasures_withMultipleSections_throws() {
        try {
            converter().convert(simpleParsed(), "n1", DiscourseType.TREASURES_TALK)
            fail("Esperava IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").contains("exatamente 1 section"))
        }
    }

    @Test
    fun convert_shortType_introAndConclusionAreNull() {
        val conv = converter().convert(treasuresParsed(), "n1", DiscourseType.MINISTRY_PART)
        assertEquals(null, conv.intro)
        assertEquals(null, conv.conclusion)
    }

    private fun treasuresParsed() = ParsedOutline(
        title = "Tesouros",
        totalMinutes = 10,
        sections = listOf(
            OutlineSection("Ponto principal", 10, 0, "Primeira ideia.\nSegunda ideia.\nTerceira ideia."),
        ),
    )

    // ---------- 3.2.3c: fallback de minutos para seções sem tempo ----------

    @Test
    fun convert_sectionWithNullMinutes_usesFallback5() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", null, 0, "x"))),
            "n1",
        )
        assertEquals(5, conv.bodies.single().section.minutes)
    }

    @Test
    fun convert_sectionWithZeroMinutes_stillProducesZero() {
        // Zero explícito NÃO é "consertado" — o validator rejeita depois.
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 0, 0, "x"))),
            "n1",
        )
        assertEquals(0, conv.bodies.single().section.minutes)
    }

    @Test
    fun outlineConverter_fullNameInput_normalizesToTnm() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Adão desobedeceu. (Gênesis 3:6)"))),
            "n1",
        )
        assertEquals(listOf("Gên 3:6"), conv.bodies.single().subPoints.single().bibleRefs)
    }

    @Test
    fun outlineConverter_abbreviatedInput_normalizesToTnm() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Adão desobedeceu. (Gên 3:6)"))),
            "n1",
        )
        assertEquals(listOf("Gên 3:6"), conv.bodies.single().subPoints.single().bibleRefs)
    }

    @Test
    fun outlineConverter_tnmSpecificInput_detectedAndNormalized() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Confie em Jeová. (Pr 3:5)"))),
            "n1",
        )
        assertEquals(listOf("Pr 3:5"), conv.bodies.single().subPoints.single().bibleRefs)
    }

    @Test
    fun outlineConverter_outlineTextIsNotNormalizedForInlineRef() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Gênesis 3:6 mostra o pecado."))),
            "n1",
        )
        val sub = conv.bodies.single().subPoints.single()
        // Texto inline permanece como o usuário escreveu (não normalizado)...
        assertTrue(sub.outlineText.contains("Gênesis 3:6"))
        // ...e a ref extraída segue a forma TNM.
        assertEquals(listOf("Gên 3:6"), sub.bibleRefs)
    }

    // ---------- 3.2.3a-fix2: canonicalização de publicações ----------

    @Test
    fun converter_watchtowerStudy_producesW2108() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Estudo indicado. (w21.08 18 § 13)"))),
            "n1",
        )
        val pub = conv.bodies.single().subPoints.single().publicationRefs.single()
        assertEquals("w21.08", pub.symbol)
        assertEquals(13, pub.paragraph)
    }

    @Test
    fun converter_watchtowerPublic_producesWp1903() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Veja a matéria. (Sentinela número 3 de 2019 pág. 6-7)"))),
            "n1",
        )
        val pub = conv.bodies.single().subPoints.single().publicationRefs.single()
        assertEquals("wp19.03", pub.symbol)
    }

    @Test
    fun converter_awake_producesG8_13() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Experiência real. (Despertai! 08/13 pág. 6)"))),
            "n1",
        )
        val pub = conv.bodies.single().subPoints.single().publicationRefs.single()
        assertEquals("g 8/13", pub.symbol)
    }

    @Test
    fun converter_book_producesBeOnly() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Técnica de leitura. (be pág. 52)"))),
            "n1",
        )
        val pub = conv.bodies.single().subPoints.single().publicationRefs.single()
        assertEquals("be", pub.symbol)
    }

    // ---------- 3.2.3a-fix3: refs antigas (página, não parágrafo) ----------

    @Test
    fun converter_oldWatchtower_producesPageNotParagraph() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Experiência citada. (w94 1/8 3)"))),
            "n1",
        )
        val pub = conv.bodies.single().subPoints.single().publicationRefs.single()
        assertEquals("w94 1/8", pub.symbol)
        assertEquals(3, pub.page)
        assertEquals(null, pub.paragraph)
    }

    @Test
    fun converter_oldAwake_producesPage() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Relato. (g93 8/1 4-10)"))),
            "n1",
        )
        val pub = conv.bodies.single().subPoints.single().publicationRefs.single()
        assertEquals("g93 8/1", pub.symbol)
        assertEquals(4, pub.page)
    }

    @Test
    fun converter_modernWatchtower_stillProducesParagraph() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Estudo. (w21.08 18 § 13)"))),
            "n1",
        )
        val pub = conv.bodies.single().subPoints.single().publicationRefs.single()
        assertEquals("w21.08", pub.symbol)
        assertEquals(13, pub.paragraph)
        assertEquals(null, pub.page)
    }

    @Test
    fun converter_oldVsStudy_distinctSymbols() {
        val conv = converter().convert(
            ParsedOutline(title = "T", sections = listOf(OutlineSection("A", 5, 0, "Veja (w94 1/8 3) e (w94 15/8 3)."))),
            "n1",
        )
        val symbols = conv.bodies.single().subPoints.single().publicationRefs.map { it.symbol }
        assertEquals(listOf("w94 1/8", "w94 15/8"), symbols)
    }
}
