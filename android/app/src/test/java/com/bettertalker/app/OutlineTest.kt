package com.bettertalker.app

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.DocExtractors
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.PastedOutlineAnalyzer
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.headingOffset
import com.bettertalker.app.data.util.headingsOf
import com.bettertalker.app.data.util.insertUnder
import com.bettertalker.app.data.util.skeletonMarkdown
import com.bettertalker.app.data.repo.CopilotRepository.ExampleKind as EK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTest {

    private val s34 = """
N.º 35		É possível viver para sempre? O que você precisa fazer?

NOTA: Ajude a assistência a meditar em como vai ser maravilhoso viver para sempre.

FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)

Precisamos estar vivos para ter esperança e fazer planos para o futuro.

COMO A VIDA ETERNA FOI PERDIDA (4 min)

Por conta própria, Adão e Eva escolheram desobedecer a Deus. (Gên 3:6)

COMO É POSSÍVEL TER VIDA ETERNA (9 min)

Jeová Deus providenciou a solução para o problema do pecado. [Leia João 3:16.]

SERÁ QUE A VIDA ETERNA VAI SER AGRADÁVEL? (8 min)

No futuro, Deus vai acabar com os problemas que tornam a vida difícil hoje.

VOCÊ VAI VIVER PARA SEMPRE? (4 min)

Cada um deve escolher se vai aceitar o presente que é o resgate.

TEMPO TOTAL: 30 MINUTOS

© 2020 Watch Tower Bible and Tract Society of Pennsylvania
""".trimIndent()

    @Test
    fun parseS34Sections() {        val o = OutlineParser.parse(s34, "S-34.docx")
        assertEquals(5, o.sections.size)
        assertEquals("FOMOS CRIADOS PARA VIVER PARA SEMPRE", o.sections[0].title)
        assertEquals(5, o.sections[0].minutes)
        assertEquals(9, o.sections[2].minutes)
        assertEquals(30, o.totalMinutes)
        assertEquals(30, OutlineParser.sumMinutes(o.sections))
        assertTrue(o.title.contains("viver para sempre", ignoreCase = true))
    }

    @Test
    fun parseRejectsEmpty() {
        val o = OutlineParser.parse("texto corrido sem tempos\nenvolvendo nada.", "x.pdf")
        assertTrue(o.sections.isEmpty())
    }

    @Test
    fun parseKeepsBodyAndPreamble() {
        val text = "N.º 35 É possível viver para sempre?\n" +
            "NOTA: ajude a assistência a meditar.\n" +
            "FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)\n" +
            "Precisamos estar vivos para ter esperança. (Despertai! 08/13 pág. 6)\n" +
            "Temos o desejo de nunca morrer. [Leia Eclesiastes 3:11.]\n" +
            "COMO A VIDA FOI PERDIDA (4 min)\n" +
            "Adão e Eva desobedeceram. (Gên 3:6)\n" +
            "TEMPO TOTAL: 30 MINUTOS"
        val o = OutlineParser.parse(text, "s.pdf")
        assertEquals(2, o.sections.size)
        assertTrue("preamble: ${o.preamble}", o.preamble.contains("NOTA"))
        val b0 = o.sections[0].body
        assertTrue("corpo1: $b0", b0.contains("Precisamos estar vivos") && b0.contains("Despertai! 08/13"))
        assertTrue(b0.contains("Eclesiastes 3:11"))
        assertTrue(o.sections[1].body.contains("Gên 3:6"))
        // refs do corpo continuam detectáveis
        val refs = RefDetector.detect(b0)
        assertTrue(refs.any { it.pubKey == "g" })
    }

    @Test
    fun jsonRoundTripWithBody() {
        val secs = listOf(
            com.bettertalker.app.data.util.OutlineSection("A", 5, 0, "Corpo \"com\" aspas\ne quebra"),
            com.bettertalker.app.data.util.OutlineSection("B", null, 1, "")
        )
        val json = OutlineParser.toJson(secs, "Preâmbulo aqui")
        val (pre, back) = OutlineParser.parseEnvelope(json)
        assertEquals("Preâmbulo aqui", pre)
        assertEquals(listOf("A", "B"), back.map { it.title })
        assertEquals("Corpo \"com\" aspas\ne quebra", back[0].body)
        assertEquals(null, back[1].minutes)
        // legado (array sem body/preâmbulo) ainda abre
        val legacy = "[{\"t\":\"X\",\"m\":3}]"
        val back2 = OutlineParser.fromJson(legacy)
        assertEquals(listOf("X"), back2.map { it.title })
        assertEquals("", back2[0].body)
    }

    @Test
    fun parseOrphanMinutesNextLine() {
        // quebra típica de PDF/DOCX: "(5 min)" cai na linha de baixo
        val text = "FOMOS CRIADOS PARA VIVER\nPARA SEMPRE\n(5 min)\n\nTexto do corpo aqui.\n\nCOMO A VIDA FOI PERDIDA (4 min)"
        val o = OutlineParser.parse(text, "s.pdf")
        assertEquals(2, o.sections.size)
        assertTrue(o.sections[0].title.contains("PARA SEMPRE"))
        assertEquals(5, o.sections[0].minutes)
    }

    @Test
    fun parseBrokenUppercaseTitle() {
        val text = "COMO É POSSÍVEL TER\nVIDA ETERNA (9 min)"
        val o = OutlineParser.parse(text, "s.pdf")
        assertEquals(1, o.sections.size)
        assertTrue(o.sections[0].title.contains("VIDA ETERNA"))
        assertEquals(9, o.sections[0].minutes)
    }

    @Test
    fun parseMinuteVariants() {
        val text = "Abertura (5 min.)\nMeio [4 minutos]\nFim(3min)\nFecho (2 MIN)"
        val o = OutlineParser.parse(text, "s.pdf")
        assertEquals(4, o.sections.size)
        assertEquals(listOf(5, 4, 3, 2), o.sections.map { it.minutes })
    }

    @Test
    fun parseIgnoresMidSentenceMinutes() {
        val text = "Fale por (5 min) sobre o tema com a assistência.\n\nABERTURA (3 min)"
        val o = OutlineParser.parse(text, "s.pdf")
        assertEquals(1, o.sections.size)
        assertEquals("ABERTURA", o.sections[0].title)
    }

    @Test
    fun jsonRoundTrip() {
        val o = OutlineParser.parse(s34, "x")
        val back = OutlineParser.fromJson(OutlineParser.toJson(o.sections))
        assertEquals(o.sections.map { it.title }, back.map { it.title })
        assertEquals(o.sections.map { it.minutes }, back.map { it.minutes })
    }

    @Test
    fun pastedPreservesRefsAndShortTopics() {
        val text = "Oração.\nJeová deseja que vivamos em paz (Sal 37:29).\n" +
            "[Siga o material do esboço.]\n© 2020 Watch Tower."
        val (cands, dropped) = PastedOutlineAnalyzer.candidates(text)
        // tópico curto preservado, ref no fim preservada, nada estrutural além do ©
        assertTrue("candidatos: ${cands.map { it.text }}", cands.any { it.text == "Oração." })
        assertTrue(cands.any { it.text.contains("(Sal 37:29)") })
        assertEquals(1, dropped)
        // instrução entre colchetes: mantida mas desmarcada (usuário decide)
        val soft = cands.filter { !it.suggested }
        assertEquals(listOf("[Siga o material do esboço.]"), soft.map { it.text })
    }

    @Test
    fun pastedMergeSuggested() {
        val cands = listOf(
            PastedOutlineAnalyzer.Candidate("Jeová deseja que todos vivam para sempre em paz na terra", 0),
            PastedOutlineAnalyzer.Candidate("Jeová deseja que todos vivam para sempre em paz e união", 1),
            PastedOutlineAnalyzer.Candidate("Os impostos devem ser pagos em dia", 2)
        )
        val merges = PastedOutlineAnalyzer.suggestMerges(cands)
        assertEquals(1, merges.size)
        assertEquals(0, merges[0].a)
        assertEquals(1, merges[0].b)
    }

    @Test
    fun headingsExtracted() {
        val md = "# Título\n\n## Desenvolvimento (9 min)\ntexto\n\n## Conclusão\nfim"
        assertEquals(listOf("Desenvolvimento (9 min)", "Conclusão"), headingsOf(md))
    }

    @Test
    fun insertUnderHeading() {
        val md = "## Abertura\ntexto A\n\n## Desenvolvimento\ntexto D"
        val (t, cursor) = insertUnder(md, "Desenvolvimento", "NOVO")
        assertTrue(t.contains("## Desenvolvimento\nNOVO"))
        assertTrue(cursor > 0)
    }

    @Test
    fun insertMissingHeadingAppends() {
        val (t, _) = insertUnder("## A", "Inexistente", "NOVO")
        assertTrue(t.endsWith("NOVO"))
    }

    @Test
    fun insertNullHeadingAppends() {
        val (t, _) = insertUnder("texto", null, "NOVO")
        assertTrue(t.endsWith("NOVO"))
    }

    private fun makeDocx(documentXml: String): java.io.File {
        val f = java.io.File.createTempFile("ttt", ".docx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
            z.write("<Types/>".toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            z.write(documentXml.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return f
    }

    @Test
    fun docxParagraphsAndSplitRuns() {
        // runs partidos no meio da palavra + parágrafos + minuto órfão
        val xml = """<w:document xmlns:w="x"><w:body>
<w:p><w:r><w:t>FOMOS CRIADOS PARA VIVER PARA SEM</w:t></w:r><w:r><w:t>PRE</w:t></w:r></w:p>
<w:p><w:r><w:t>(5 min)</w:t></w:r></w:p>
<w:p><w:r><w:t>Texto do corpo aqui.</w:t></w:r></w:p>
<w:p><w:r><w:t>COMO A VIDA FOI PERDIDA (4 min)</w:t></w:r></w:p>
</w:body></w:document>"""
        val f = makeDocx(xml)
        try {
            val o = OutlineParser.parse(DocExtractors.readDocx(f), "s.docx")
            assertEquals(2, o.sections.size)
            assertTrue(o.sections[0].title.contains("SEMPRE"))
            assertEquals(5, o.sections[0].minutes)
            assertEquals(4, o.sections[1].minutes)
        } finally {
            f.delete()
        }
    }

    @Test
    fun docxTableLayout() {
        // S-34 em tabela: título e tempo em células vizinhas
        val xml = """<w:document xmlns:w="x"><w:body><w:tbl>
<w:tr><w:tc><w:p><w:r><w:t>ABERTURA</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>(5 min)</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>DESENVOLVIMENTO</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>(9 min)</w:t></w:r></w:p></w:tc></w:tr>
</w:tbl></w:body></w:document>"""
        val f = makeDocx(xml)
        try {
            val o = OutlineParser.parse(DocExtractors.readDocx(f), "s.docx")
            assertEquals(2, o.sections.size)
            assertEquals("ABERTURA", o.sections[0].title)
            assertEquals(listOf(5, 9), o.sections.map { it.minutes })
        } finally {
            f.delete()
        }
    }

    @Test
    fun outlineRefsDetectedAndResolved() {
        // texto típico de esboço S-34
        val text = "A vida parece curta. (Despertai! 08/13 pág. 6)\n" +
            "Deus criou os humanos. (Sentinela número 3 de 2019 pág. 6-7)\n" +
            "Veja o livro Beneficie-se, páginas 52-55.\n" +
            "Leia Eclesiastes 3:11."
        val detected = RefDetector.detect(text)
        assertTrue("refs: $detected", detected.size >= 3)
        val json = RefDetector.detectedToJson(detected)
        val back = RefDetector.detectedFromJson(json)
        assertEquals(detected.map { it.editionKey }, back.map { it.editionKey })
        // sem anexos: tudo faltando, com URL de download
        val missing = RefDetector.resolve(back, emptyList())
        assertTrue(missing.all { !it.resolved && it.downloadUrl.isNotEmpty() })
        // com a edição exata baixada: resolve
        val atts = listOf(
            AttachmentEntity(
                "a1", null, "g_T_201308.pdf", "pdf", 100, "/x", true, 1L
            )
        )
        val resolved = RefDetector.resolve(back, atts)
        val g = resolved.first { it.ref.pubKey == "g" }
        assertTrue("Despertai! 08/13 resolve: $g", g.resolved && g.fileName == "g_T_201308.pdf")
        assertTrue(resolved.first { it.ref.pubKey == "wp" }.resolved.not())
    }

    @Test
    fun oldDayMonthCodes() {
        // w99 1/5 = pública dia 1º; w99 15/5 = estudo dia 15
        val refs = RefDetector.detect("Ver w99 1/5 e também w99 15/5 sobre o tema.")
        assertEquals(2, refs.size)
        val pub = refs.first { it.editionKey == "w|1999|5|1" }
        val study = refs.first { it.editionKey == "w|1999|5|15" }
        assertTrue(pub.label.contains("pública"))
        assertTrue(study.label.contains("estudo"))
        val (pubUrl, _, pubExact) = RefDetector.downloadUrl(pub)
        val (stUrl, _, stExact) = RefDetector.downloadUrl(study)
        assertTrue(pubExact)
        assertTrue(stExact)
        assertTrue(pubUrl.endsWith("/revistas/w19990501/"))
        assertTrue(stUrl.endsWith("/revistas/w19990515/"))
    }

    @Test
    fun specificMagazineUrls() {
        fun url(raw: String): Triple<String, String, Boolean> {
            val ref = RefDetector.detect(raw).firstOrNull()
                ?: throw AssertionError("nada detectado em: $raw")
            return RefDetector.downloadUrl(ref)
        }
        // estudo mensal moderna (verificado dez/2024)
        assertEquals(
            "https://www.jw.org/pt/biblioteca/revistas/sentinela-estudo-dezembro-de-2024/",
            url("conforme w24.12 sobre o tema").first
        )
        // estudo por extenso (verificado mar/2019)
        assertEquals(
            "https://www.jw.org/pt/biblioteca/revistas/sentinela-estudo-marco-de-2019/",
            url("A Sentinela de março de 2019 traz o ponto").first
        )
        // Despertai! mensal antiga (verificado g201308)
        assertEquals(
            "https://www.jw.org/pt/biblioteca/revistas/g201308/",
            url("(Despertai! 08/13 pág. 6)").first
        )
        // Despertai! numerada nova (verificado no1-2024)
        assertEquals(
            "https://www.jw.org/pt/biblioteca/revistas/despertai-no1-2024/",
            url("Despertai! N.º 1 2024 sobre respeito").first
        )
        // pública numerada recente (verificado no1-2024)
        val (wpUrl, _, wpExact) = url("Sentinela número 1 de 2024, página 5")
        assertTrue(wpExact)
        assertEquals("https://www.jw.org/pt/biblioteca/revistas/sentinela-no1-2024/", wpUrl)
        // pública numerada antiga: sufixo inderivável -> fallback honesto
        val (oldUrl, _, oldExact) = url("Sentinela número 3 de 2019, páginas 6-7")
        assertTrue(!oldExact)
        assertEquals("https://www.jw.org/pt/biblioteca/revistas/", oldUrl)
    }

    @Test
    fun bookFinderUrl() {
        // sigla de livro sem landing curada -> finder resolve (verificado com th)
        val direct = RefDetector.DetectedRef("lff", RefDetector.Kind.BOOK, "lff", "book|lff", "Lff")
        val (url, _, exact) = RefDetector.downloadUrl(direct)
        assertTrue(exact)
        assertEquals("https://www.jw.org/finder?wtlocale=T&pub=lff&srcid=share", url)
    }

    @Test
    fun dayEditionMatching() {
        val refs = RefDetector.detect("ver w99 15/5")
        val atts = listOf(
            AttachmentEntity("a1", null, "w_T_19990515.pdf", "pdf", 100, "/x", true, 1L),
            AttachmentEntity("a2", null, "w_T_19990501.pdf", "pdf", 100, "/x", true, 1L)
        )
        val resolved = RefDetector.resolve(refs, atts)
        assertEquals("w_T_19990515.pdf", resolved.first().fileName)
    }

    @Test
    fun apiQueryMapping() {        val api = com.bettertalker.app.data.util.JwMediaApi::apiQuery
        // mensais com arquivo direto derivável
        assertEquals("g" to "201308", api("g|2013|8"))
        assertEquals("w" to "202412", api("w|2024|12"))
        // datadas antigas, numeradas e livros: só página
        assertEquals(null, api("w|1999|5|1"))
        assertEquals(null, api("wp|2019|3"))
        assertEquals(null, api("gn|2024|1"))
        assertEquals(null, api("book|be"))
    }

    @Test
    fun pubCatalogLoaded() {
        val cat = com.bettertalker.app.data.util.PubCatalog
        assertTrue(cat.size() >= 100)
        assertEquals("Seja Feliz para Sempre!", cat.titleOf("lff"))
        assertEquals("Entenda a Bíblia", cat.titleOf("bhs"))
        assertTrue(cat.isSymbol("jy"))
        // "na" colide com preposição: fora da detecção por sigla
        assertTrue(!cat.isSymbol("na"))
        assertTrue(!cat.isSymbol("xyz"))
    }

    @Test
    fun symbolRefDetection() {
        val refs = RefDetector.detect("conforme lff cap. 5 e be pág. 52 sobre o tema")
        val lff = refs.firstOrNull { it.pubKey == "lff" }
        assertNotNull(lff)
        assertEquals("book|lff", lff!!.editionKey)
        assertEquals("Seja Feliz para Sempre!", lff.label)
        val (url, _, exact) = RefDetector.downloadUrl(lff)
        assertTrue(exact)
        assertEquals("https://www.jw.org/finder?wtlocale=T&pub=lff&srcid=share", url)
    }

    @Test
    fun prepositionsNotDetected() {
        assertTrue(RefDetector.detect("leia na página 5 com atenção").isEmpty())
        assertTrue(RefDetector.detect("veja em lição 3 o ponto").isEmpty())
    }

    @Test
    fun symbolDedupesNameMatch() {
        // sigla + nome da mesma pub = 1 ref só
        val refs = RefDetector.detect("Veja o livro Beneficie-se, be pág. 52-55.")
        assertEquals(1, refs.filter { it.pubKey == "be" }.size)
    }

    @Test
    fun symbolTokenMatching() {
        val refs = RefDetector.detect("estude lff cap. 5 hoje")
        val atts = listOf(
            AttachmentEntity("a1", null, "lff_T.pdf", "pdf", 100, "/x", true, 1L)
        )
        val st = RefDetector.resolve(refs, atts).first()
        assertTrue("resolve por token da sigla: $st", st.resolved && st.fileName == "lff_T.pdf")
    }

    @Test
    fun s34FullFixture() {
        // esboço S-34 N.º 35 integral: todas as publicações, sem bíblicos, sem dupes
        val text = """
N.º 35 É possível viver para sempre? O que você precisa fazer?
NOTA: Ajude a assistência a meditar.
FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)
Precisamos estar vivos para ter esperança.
O tempo passa muito rápido. (Despertai! 08/13 pág. 6)
Temos o desejo de nunca morrer. [Leia Eclesiastes 3:11.] (Despertai! 08/13 pág. 8 parág. 1-2)
Deus criou os humanos. (Gên 1:26, 31; Sentinela número 3 de 2019 pág. 6-7)
Adão e Eva poderiam ter vivido para sempre. (Gên 2:16, 17)
COMO A VIDA ETERNA FOI PERDIDA (4 min)
Adão e Eva desobedeceram. (Gên 3:6) [Imagem 1]
Eles foram expulsos. (Gên 3:19, 22, 23; 5:5)
Adão transmitiu o pecado. [Leia Romanos 5:12.]
O propósito não mudou. (Despertai! 12/08 pág. 7)
COMO É POSSÍVEL TER VIDA ETERNA (9 min)
Os esforços humanos não vão trazer vida eterna.
Os avanços aumentaram a expectativa. (Sal 90:10; Sentinela número 3 de 2019 pág. 5 parág. 3-4)
Jeová providenciou a solução. [Leia João 3:16.]
Jesus deu sua vida. (Mt 20:28; Ro 5:19; Entenda a Bíblia cap. 5 parág. 10-11) [Imagem 2]
Muitos vão viver para sempre. (Sal 37:29)
Milhões vão sobreviver. (Ap 7:9, 14; 20:13)
SERÁ QUE A VIDA ETERNA VAI SER AGRADÁVEL? (8 min)
Toda a maldade vai deixar de existir. (Sal 37:10, 11)
Serão eliminadas a doença e a morte. (Is 25:8; 33:24; Ap 21:3, 4)
Deus vai reverter o envelhecimento. (Jó 33:24, 25)
A humanidade vai viver em paz. (Sal 72:7, 16)
E o mais importante. (Ro 11:33)
VOCÊ VAI VIVER PARA SEMPRE? (4 min)
Deus promete vida eterna. (Jo 3:36; Sentinela número 2 de 2017 pág. 7 parág. 1)
Faça o mais importante. (Jo 17:3)
[Convide os novos a obter conhecimento.]
[Siga de perto o material. Veja o livro Beneficie-se, páginas 52-55, 166-169.]
TEMPO TOTAL: 30 MINUTOS
© 2020 Watch Tower Bible and Tract Society of Pennsylvania
S-34-T N.º 35 5/20
""".trimIndent()
        val detected = RefDetector.detect(text)
        assertEquals(
            setOf("g|2013|8", "g|2008|12", "wp|2019|3", "wp|2017|2", "book|bhs", "book|be"),
            detected.map { it.editionKey }.toSet()
        )
        // sem duplicatas (08/13 e 3/2019 citados 2x)
        assertEquals(6, detected.size)
        // bhs com título do catálogo e finder
        val bhs = detected.first { it.pubKey == "bhs" }
        assertEquals("Entenda a Bíblia", bhs.label)
        val (bhsUrl, _, bhsExact) = RefDetector.downloadUrl(bhs)
        assertTrue(bhsExact && bhsUrl.contains("pub=bhs"))
        // numeradas antigas: sonda via API
        val wp = RefDetector.resolve(detected, emptyList()).first { it.ref.pubKey == "wp" }
        assertEquals("wp", wp.probePub)
        assertEquals(2019, wp.probeYear)
        // sem anexos: tudo faltando com destino
        val missing = RefDetector.resolve(detected, emptyList())
        assertTrue(missing.all { !it.resolved && it.downloadUrl.isNotEmpty() })
    }

    @Test
    fun headingOffsetFindsSection() {        val text = "Intro\n\n## Desenvolvimento (9 min)\n\ntexto D\n\n## Conclusão\nfim"
        val at = headingOffset(text, "Desenvolvimento (9 min)")
        assertNotNull(at)
        assertTrue(text.substring(at!!).startsWith("texto D"))
        assertNull(headingOffset(text, "Inexistente"))
        assertNull(headingOffset(text, ""))
    }

    @Test
    fun skeletonMarkdownBuilds() {        val secs = listOf(
            com.bettertalker.app.data.util.OutlineSection("Abertura", 5, 0, "Corpo A."),
            com.bettertalker.app.data.util.OutlineSection("Fim", null, 1, "")
        )
        val md = skeletonMarkdown(secs, "Preâmbulo.")
        assertTrue(md.startsWith("Preâmbulo."))
        assertTrue(md.contains("## Abertura (5 min)\n\nCorpo A."))
        assertTrue(md.contains("## Fim"))
    }

    @Test
    fun richTextMarkdownRoundTrip() {
        // headless: valida o par setMarkdown/toMarkdown usado pelo editor/Copilot
        val state = com.mohamedrejeb.richeditor.model.RichTextState()
        state.setMarkdown("# T\n\n**b** *i*\n\n- a\n- b")
        val md = state.toMarkdown()
        assertTrue("roundtrip: $md", md.contains("# T") && md.contains("**b**"))
    }

    @Test
    fun pastedIndentLevels() {        val text = "Tesouros da Palavra de Deus.\n" +
            "  Jeová deseja que vivamos em paz.\n" +
            "    Porque ele nos ama primeiro.\n" +
            "  Oração.\n" +
            "Conclusão."
        val (cands, _) = PastedOutlineAnalyzer.candidates(text)
        assertEquals(listOf(0, 1, 2, 1, 0), cands.map { it.level })
        assertEquals(
            listOf(
                "Tesouros da Palavra de Deus.",
                "Jeová deseja que vivamos em paz.",
                "Porque ele nos ama primeiro.",
                "Oração.",
                "Conclusão."
            ),
            cands.map { it.text }
        )
    }

    @Test
    fun pastedParenAttachesToPrevious() {
        val (cands, _) = PastedOutlineAnalyzer.candidates(
            "Jeová deseja que vivamos em paz.\n(Sal 37:29).\nOração."
        )
        assertEquals(2, cands.size)
        assertTrue(cands[0].text.contains("(Sal 37:29)"))
        assertEquals("Oração.", cands[1].text)
    }

    @Test
    fun pastedParenFirstStaysRow() {
        val (cands, _) = PastedOutlineAnalyzer.candidates("(Sal 37:29).\nOração.")
        assertEquals(2, cands.size)
        assertTrue(!cands[0].suggested) // sem anterior: linha própria desmarcada
    }

    @Test
    fun jsonLevelRoundTrip() {
        val secs = listOf(
            com.bettertalker.app.data.util.OutlineSection("A", 5, 0, "", 0),
            com.bettertalker.app.data.util.OutlineSection("A.1", null, 1, "corpo", 1)
        )
        val back = OutlineParser.fromJson(OutlineParser.toJson(secs, ""))
        assertEquals(listOf(0, 1), back.map { it.level })
        assertEquals("corpo", back[1].body)
        // legado sem level abre com 0
        val legacy = OutlineParser.fromJson("[{\"t\":\"X\",\"m\":3}]")
        assertEquals(0, legacy[0].level)
    }

    @Test
    fun skeletonNestsLevels() {
        val secs = listOf(
            com.bettertalker.app.data.util.OutlineSection("A", 5, 0, "", 0),
            com.bettertalker.app.data.util.OutlineSection("A.1", null, 1, "", 1)
        )
        val md = com.bettertalker.app.data.util.skeletonMarkdown(secs, "")
        assertTrue(md.contains("## A (5 min)"))
        assertTrue(md.contains("### A.1"))
        assertEquals(listOf("A (5 min)", "A.1"), com.bettertalker.app.data.util.headingsOf(md))
    }

    @Test
    fun headingOffsetFindsNested() {
        val md = "## A\n\ntexto\n\n### A.1\n\ncorpo"
        val at = com.bettertalker.app.data.util.headingOffset(md, "A.1")
        assertNotNull(at)
        assertTrue(md.substring(at!!).startsWith("corpo"))
    }


    @Test
    fun markerLevelDirect() {        val m = PastedOutlineAnalyzer::markerLevel
        assertEquals(0, m("1. Tesouros."))
        assertEquals(1, m("a) Ponto."))
        assertEquals(1, m("- item."))
        assertEquals(2, m("ii. Item."))
        assertEquals(null, m("A vida eterna é real."))
        assertEquals(null, m("Tesouros da Palavra de Deus."))
    }

    @Test
    fun pastedNumberingLevelsWhenFlat() {
        val text = "1. Tesouros da Palavra de Deus.\na) Jeová deseja paz.\nb) Oremos sempre.\n2. Conclusão."
        val (cands, _) = PastedOutlineAnalyzer.candidates(text)
        assertEquals(listOf(0, 1, 1, 0), cands.map { it.level })
    }

    @Test
    fun pastedArticleStartNotLeveled() {
        // frase comum começando com "a " NÃO vira subnível
        val (cands, _) = PastedOutlineAnalyzer.candidates("Tesouros.\nA vida eterna é real.")
        assertTrue(cands.all { it.level == 0 })
    }

    @Test
    fun structuredBodyKeepsIndent() {
        val text = "SEÇÃO (5 min)\n  Subponto recuado aqui.\nTexto normal."
        val o = OutlineParser.parse(text, "s.pdf")
        assertEquals(1, o.sections.size)
        assertTrue("corpo: ${o.sections[0].body}", o.sections[0].body.contains("  Subponto"))
    }

    @Test
    fun skeletonIndentsLevels() {
        val secs = listOf(
            com.bettertalker.app.data.util.OutlineSection("A", 5, 0, "", 0),
            com.bettertalker.app.data.util.OutlineSection("A.1", null, 1, "", 1),
            com.bettertalker.app.data.util.OutlineSection("A.1.a", null, 2, "", 2)
        )
        val md = com.bettertalker.app.data.util.skeletonMarkdown(secs, "")
        assertTrue(md.contains("## A (5 min)"))
        assertTrue(md.contains("  ### A.1"))
        // nível 2: 3 espaços no máximo (4+ viraria bloco de código)
        assertTrue(md.contains("   #### A.1.a"))
        assertEquals(listOf("A (5 min)", "A.1", "A.1.a"), com.bettertalker.app.data.util.headingsOf(md))
    }

    @Test
    fun pastedTitleFirst() {
        // exemplo real estilo mwb: título (N min) + tópicos num parágrafo só
        val text = "Jeová apoia aqueles que apoiam o Seu Reino (10 min) " +
            "Em uma época de divisão política, Jeremias pregou uma mensagem que não agradava as pessoas. " +
            "(Jer. 37:6-10; jr 27 § 22) Ele foi perseguido porque entenderam errado. " +
            "(Jer. 37:13-15; jr 28 § 23) Jeová ajudou Jeremias preso. " +
            "(Jer. 37:21; w08 15/10 11 § 18) PERGUNTE-SE: ‘Como o exemplo nos incentiva?’"
        val (title, rest) = PastedOutlineAnalyzer.splitTitle(text)
        assertEquals("Jeová apoia aqueles que apoiam o Seu Reino (10 min)", title)
        assertTrue(rest.startsWith("Em uma época"))
        val (cands, _) = PastedOutlineAnalyzer.candidates(rest)
        assertEquals(4, cands.size)
        assertTrue(cands[0].text.contains("(Jer. 37:6-10; jr 27 § 22)"))
        assertTrue(cands[1].text.startsWith("Ele foi perseguido"))
        assertTrue(cands[2].text.contains("w08 15/10"))
        assertTrue(cands[3].text.startsWith("PERGUNTE-SE"))
    }

    @Test
    fun pastedTitleOnlyAtStart() {
        // (N min) longe do início NÃO vira título
        val far = "Introdução. " + "palavra ".repeat(60) + "(5 min) resto."
        val (title, rest) = PastedOutlineAnalyzer.splitTitle(far)
        assertNull(title)
        assertEquals(far, rest)
    }

    @Test
    fun bookSectionRef() {
        // "jr 27 § 22": livro + estudo + parágrafo
        val refs = RefDetector.detect("conforme jr 27 § 22 sobre o tema")
        val jr = refs.firstOrNull { it.pubKey == "jr" }
        assertNotNull(jr)
        assertEquals("book|jr", jr!!.editionKey)
        // "ver § 5" sozinho não vira ref (sem sigla)
        assertTrue(RefDetector.detect("ver § 5 do estudo").none { it.editionKey == "book|ver" })
    }

    @Test
    fun wDayPageLabel() {
        val refs = RefDetector.detect("conforme w08 15/10 11 § 18 sobre ele")
        val w = refs.firstOrNull { it.editionKey == "w|2008|10|15" }
        assertNotNull(w)
        assertTrue("rótulo: ${w!!.label}", w.label.contains("pág. 11") && w.label.contains("§ 18"))
    }

    @Test
    fun chatIntentRouting() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertTrue(ci.classify("me resuma o discurso") is com.bettertalker.app.data.util.ChatIntent.Intent.Summarize)
        assertTrue(ci.classify("quais referências faltam?") is com.bettertalker.app.data.util.ChatIntent.Intent.CheckRefs)
        assertTrue(ci.classify("verificar refs") is com.bettertalker.app.data.util.ChatIntent.Intent.CheckRefs)
        val ins = ci.classify("insere a segunda ideia")
        assertTrue(ins is com.bettertalker.app.data.util.ChatIntent.Intent.Insert && (ins as com.bettertalker.app.data.util.ChatIntent.Intent.Insert).index == 1)
        assertTrue(ci.classify("coloca a 1") is com.bettertalker.app.data.util.ChatIntent.Intent.Insert)
        val id = ci.classify("ideias para a conclusão", listOf(com.bettertalker.app.data.util.ChatIntent.SectionRef("Abertura"), com.bettertalker.app.data.util.ChatIntent.SectionRef("Conclusão")))
        assertTrue(id is com.bettertalker.app.data.util.ChatIntent.Intent.Ideas &&
            (id as com.bettertalker.app.data.util.ChatIntent.Intent.Ideas).sectionHint == "Conclusão")
        assertTrue(ci.classify("ideias gerais") is com.bettertalker.app.data.util.ChatIntent.Intent.Ideas)
        assertTrue(ci.classify("oi") is com.bettertalker.app.data.util.ChatIntent.Intent.Help)
        assertTrue(ci.classify("obrigado!") is com.bettertalker.app.data.util.ChatIntent.Intent.Thanks)
        val ask = ci.classify("o que a Bíblia diz sobre fé?")
        assertTrue(ask is com.bettertalker.app.data.util.ChatIntent.Intent.Ask)
        assertNull((ci.classify("insere") as com.bettertalker.app.data.util.ChatIntent.Intent.Insert).index)
    }

    @Test
    fun chatIntentCopilotActions() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertTrue(ci.classify("refs do esboço") is com.bettertalker.app.data.util.ChatIntent.Intent.OutlineRefs)
        assertTrue(ci.classify("mostre as seções") is com.bettertalker.app.data.util.ChatIntent.Intent.Sections)
        assertTrue(ci.classify("qual a estrutura do esboço?") is com.bettertalker.app.data.util.ChatIntent.Intent.Sections)
        assertTrue(ci.classify("reinserir esqueleto") is com.bettertalker.app.data.util.ChatIntent.Intent.Skeleton)
        assertTrue(ci.classify("desvincule o esboço") is com.bettertalker.app.data.util.ChatIntent.Intent.Unlink)
        assertTrue(ci.classify("remover o esboço") is com.bettertalker.app.data.util.ChatIntent.Intent.Unlink)
        assertTrue(ci.classify("baixar as bases") is com.bettertalker.app.data.util.ChatIntent.Intent.Bases)
        // sem regressão: refs da nota continuam CheckRefs, ideias continuam Ideas
        assertTrue(ci.classify("verificar refs") is com.bettertalker.app.data.util.ChatIntent.Intent.CheckRefs)
        assertTrue(ci.classify("quais referências faltam?") is com.bettertalker.app.data.util.ChatIntent.Intent.CheckRefs)
        val id = ci.classify("ideias para a conclusão", listOf(com.bettertalker.app.data.util.ChatIntent.SectionRef("Abertura"), com.bettertalker.app.data.util.ChatIntent.SectionRef("Conclusão")))
        assertTrue(id is com.bettertalker.app.data.util.ChatIntent.Intent.Ideas)
        // "tirar dúvida" não desvincula nada
        assertTrue(ci.classify("tire uma dúvida sobre fé") is com.bettertalker.app.data.util.ChatIntent.Intent.Ask)
    }

    @Test
    fun chatFollowUpMore() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertTrue(ci.isFollowUpMore("mais"))
        assertTrue(ci.isFollowUpMore("manda outra ideia"))
        assertTrue(ci.isFollowUpMore("fala mais sobre fé"))
        assertTrue(ci.isFollowUpMore("detalhe mais"))
        assertTrue(ci.isFollowUpMore("outra, por favor"))
        assertFalse(ci.isFollowUpMore("jamais fiz isso"))
        assertFalse(ci.isFollowUpMore("o que dizem sobre fé?"))
        assertFalse(ci.isFollowUpMore("ideias para a conclusão"))
    }

    @Test
    fun chatStripMoreWords() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertEquals("sobre fe", ci.stripMoreWords("fala mais sobre fé"))
        assertEquals("", ci.stripMoreWords("mais"))
        assertEquals("", ci.stripMoreWords("manda outra ideia"))
    }

    @Test
    fun chatMatchSectionTitle() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        val titles = listOf("Abertura", "Conclusão")
        assertEquals("Conclusão", ci.matchSectionTitle("a conclusão", titles))
        assertEquals("Abertura", ci.matchSectionTitle("ideias para abertura", titles))
        assertNull(ci.matchSectionTitle("sobre fé", titles))
    }

    @Test
    fun pastedBodiesGroupChildren() {
        val text = "Tesouros da Palavra de Deus.\n" +
            "  Jeová deseja que vivamos em paz (Sal 37:29).\n" +
            "    Porque ele nos ama primeiro.\n" +
            "Conclusão."
        val (cands, _) = PastedOutlineAnalyzer.candidates(text)
        assertEquals(4, cands.size)
        val bodies = PastedOutlineAnalyzer.bodies(cands)
        assertEquals(4, bodies.size)
        // pai agrega subtópicos e refs; tópico plano continua sem corpo
        assertTrue("corpo: ${bodies[0]}", bodies[0].contains("Jeová deseja que vivamos em paz"))
        assertTrue(bodies[0].contains("Porque ele nos ama primeiro"))
        assertTrue(bodies[1].contains("Porque ele nos ama primeiro"))
        assertEquals("", bodies[2])
        assertEquals("", bodies[3])
    }

    @Test
    fun chatMatchSectionByBody() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        val secs = listOf(
            com.bettertalker.app.data.util.ChatIntent.SectionRef("Tesouros", "Jeová deseja que vivamos em paz (Sal 37:29)"),
            com.bettertalker.app.data.util.ChatIntent.SectionRef("Conclusão", "Aplicação para esta semana")
        )
        // título continua ganhando
        assertEquals("Conclusão", ci.matchSection("ideias para a conclusão", secs))
        // subtópico/termo do corpo acha a seção
        assertEquals("Tesouros", ci.matchSection("deseja que vivamos em paz", secs))
        // 1 palavra comum não basta
        assertNull(ci.matchSection("sobre fé", secs))
        assertNull(ci.matchSection("ideias", secs))
    }

    @Test
    fun chatSectionsCodecLevelTolerant() {        val codec = com.bettertalker.app.data.util.ChatCodec
        val secs = listOf(
            com.bettertalker.app.data.util.ChatCodec.SecItem("Abertura", 3, 0),
            com.bettertalker.app.data.util.ChatCodec.SecItem("Detalhe", null, 1)
        )
        val back = codec.sectionsFromJson(codec.sectionsToJson(secs))
        assertEquals(secs, back)
        // mensagens antigas (sem "l") continuam lendo
        val legacy = codec.sectionsFromJson("""[{"t":"X","m":5}]""")
        assertEquals(listOf(com.bettertalker.app.data.util.ChatCodec.SecItem("X", 5, 0)), legacy)
    }

    @Test
    fun chatIntentNumbers() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertEquals(0, ci.parseNumber("a primeira") ?: -1)
        assertEquals(2, ci.parseNumber("coloca a terceira") ?: -1)
        assertEquals(4, ci.parseNumber("5") ?: -1)
        assertNull(ci.parseNumber("sem número"))
    }

    @Test
    fun chatCardCodecRoundTrip() {
        val c = com.bettertalker.app.data.repo.IdeaCard(
            "T \"com\" aspas", "corpo\nquebra", "snip", "http://x",
            "fonte", "seção", "motivo"
        )
        val back = com.bettertalker.app.data.util.ChatCodec.cardsFromJson(
            com.bettertalker.app.data.util.ChatCodec.cardsToJson(listOf(c))
        )
        assertEquals(1, back.size)
        assertEquals(c, back[0])
        assertEquals(emptyList<com.bettertalker.app.data.repo.IdeaCard>(), com.bettertalker.app.data.util.ChatCodec.cardsFromJson("lixo"))
    }

    @Test
    fun chatCardInsertableFlag() {
        // orientação (guia) nunca vai para a nota
        val g = com.bettertalker.app.data.repo.IdeaCard(
            "Orientação", "instrução", "", "", "be", "Seção", "guia",
            insertable = false
        )
        assertFalse(g.insertable)
        val back = com.bettertalker.app.data.util.ChatCodec.cardsFromJson(
            com.bettertalker.app.data.util.ChatCodec.cardsToJson(listOf(g))
        )
        assertEquals(1, back.size)
        assertFalse(back[0].insertable)
        // mensagens antigas (sem "f") continuam inseríveis
        val legacy = com.bettertalker.app.data.util.ChatCodec.cardsFromJson(
            """[{"t":"T","b":"B","s":"","u":"","src":"","sec":"","pr":""}]"""
        )
        assertEquals(1, legacy.size)
        assertTrue(legacy[0].insertable)
    }

    @Test
    fun chatMsgCodecRoundTrip() {
        val codec = com.bettertalker.app.data.util.ChatCodec
        val payload = codec.escMap(mapOf(
            "text" to "Olá! \"aspas\", quebra\ne backslash \\ aqui",
            "slots" to "be,th"
        ))
        val back = codec.unescMap(payload)
        assertEquals("Olá! \"aspas\", quebra\ne backslash \\ aqui", back["text"])
        assertEquals("be,th", back["slots"])
        assertEquals(emptyMap<String, String>(), codec.unescMap("lixo"))
    }

    @Test
    fun chatMsgCodecNestedCards() {
        val codec = com.bettertalker.app.data.util.ChatCodec
        val cards = listOf(
            com.bettertalker.app.data.repo.IdeaCard(
                "T \"com\" aspas", "corpo\nmultilinha \\ barra", "snip", "http://x",
                "fonte", "seção", "motivo"
            )
        )
        val payload = codec.escMap(mapOf(
            "section" to "Conclusão",
            "cards" to codec.cardsToJson(cards)
        ))
        val m = codec.unescMap(payload)
        assertEquals("Conclusão", m["section"])
        val back = codec.cardsFromJson(m["cards"].orEmpty())
        assertEquals(1, back.size)
        assertEquals(cards[0], back[0])
    }

    @Test
    fun refDetectFullTitle() {
        val refs = RefDetector.detect("conforme Seja Feliz para Sempre, capítulo 5, sobre a oração")
        val lff = refs.firstOrNull { it.editionKey == "book|lff" }
        assertNotNull("refs: ${refs.map { it.editionKey }}", lff)
    }

    @Test
    fun refDetectBareSymbolNumber() {
        val a = RefDetector.detect("ver lff 27 sobre ele")
        assertTrue(a.any { it.editionKey == "book|lff" })
        val b = RefDetector.detect("como diz (jy 15) sobre Jesus")
        assertTrue(b.any { it.editionKey == "book|jy" })
        // código de revista não vira livro
        val c = RefDetector.detect("w 24 é bom")
        assertTrue(c.none { it.kind == RefDetector.Kind.BOOK })
    }

    @Test
    fun refDetectNoFalsePositiveCommonWords() {
        assertTrue(RefDetector.detect("o governo humano é falho").none { it.editionKey == "book|bp" })
        assertTrue(RefDetector.detect("fui à escola ontem").none { it.editionKey == "book|sj" })
        // com pista de estudo, vale
        assertTrue(RefDetector.detect("Escola, lição 5, sobre leitura").any { it.editionKey == "book|sj" })
    }

    @Test
    fun refMatchByFileTitle() {
        val att = com.bettertalker.app.data.db.AttachmentEntity(
            id = "a1", noteId = null, fileName = "Seja Feliz para Sempre.pdf",
            kind = "pdf", sizeBytes = 1, appPath = "/x", indexed = true, addedAt = 0
        )
        val ref = RefDetector.DetectedRef("lff 27", RefDetector.Kind.BOOK, "lff", "book|lff", "x")
        assertEquals("a1", RefDetector.matchEdition(ref, listOf(att))?.id)
    }

    @Test
    fun partitionGuideSplitsGuideAndContent() {
        val mkHit = { src: String ->
            com.bettertalker.app.data.repo.ScopedHit(
                com.bettertalker.app.data.db.PassageEntity("p-$src", "a", "texto", "texto"),
                src
            )
        }
        val guide = mkHit("Beneficie-se da Escola do Ministério Teocrático")
        val content = mkHit("Seja Feliz para Sempre!")
        val (g, c) = com.bettertalker.app.data.repo.partitionGuideHits(listOf(guide, content))
        assertEquals(listOf(guide), g)
        assertEquals(listOf(content), c)
    }

    @Test
    fun exampleKindForPositionAndWords() {
        fun k(first: Boolean, last: Boolean, words: String) =
            com.bettertalker.app.data.repo.exampleKindFor(first, last, words)
        assertEquals(EK.INTRO, k(true, false, ""))
        assertEquals(EK.CONCLUSION, k(false, true, ""))
        assertEquals(EK.ILLUSTRATION, k(false, false, ""))
        assertEquals(EK.INTRO, k(false, false, "como introduzir o tema"))
        assertEquals(EK.INTRO, k(false, true, "exemplo de introducao"))
        assertEquals(EK.CONCLUSION, k(true, false, "como concluir"))
        assertEquals(EK.QUESTION, k(false, false, "pergunta inicial"))
        assertEquals(EK.ILLUSTRATION, k(false, false, "ilustracao para a secao"))
    }

    @Test
    fun exampleGuideQueryDistinct() {
        val qs = EK.values().map { com.bettertalker.app.data.repo.exampleGuideQuery(it) }
        assertEquals(4, qs.toSet().size)
        assertTrue(qs.all { it.isNotBlank() })
    }

    @Test
    fun chatIntentExampleRouting() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        val secs = listOf(
            com.bettertalker.app.data.util.ChatIntent.SectionRef("Introdução"),
            com.bettertalker.app.data.util.ChatIntent.SectionRef("Conclusão")
        )
        val ex = ci.classify("exemplo para a conclusão", secs)
        assertTrue(ex is com.bettertalker.app.data.util.ChatIntent.Intent.Example)
        ex as com.bettertalker.app.data.util.ChatIntent.Intent.Example
        assertEquals("conclusion", ex.kind)
        assertEquals("Conclusão", ex.sectionHint)
        val intro = ci.classify("como introduzir o tema")
        assertTrue(intro is com.bettertalker.app.data.util.ChatIntent.Intent.Example &&
            (intro as com.bettertalker.app.data.util.ChatIntent.Intent.Example).kind == "intro")
        val ill = ci.classify("me dá uma ilustração")
        assertTrue(ill is com.bettertalker.app.data.util.ChatIntent.Intent.Example &&
            (ill as com.bettertalker.app.data.util.ChatIntent.Intent.Example).kind == "illustration")
        val q = ci.classify("pergunta inicial")
        assertTrue(q is com.bettertalker.app.data.util.ChatIntent.Intent.Example &&
            (q as com.bettertalker.app.data.util.ChatIntent.Intent.Example).kind == "question")
        val bare = ci.classify("exemplo")
        assertTrue(bare is com.bettertalker.app.data.util.ChatIntent.Intent.Example &&
            (bare as com.bettertalker.app.data.util.ChatIntent.Intent.Example).kind == null)
        assertEquals("intro", ci.exampleKindHint("Exemplo de introdução"))
        // sem regressão
        val id = ci.classify("ideias para a conclusão", secs)
        assertTrue(id is com.bettertalker.app.data.util.ChatIntent.Intent.Ideas)
        assertTrue(ci.classify("o que dizem sobre fé?") is com.bettertalker.app.data.util.ChatIntent.Intent.Ask)
        assertTrue(ci.classify("insere a segunda") is com.bettertalker.app.data.util.ChatIntent.Intent.Insert)
    }

    @Test
    fun chatIntentSyncRouting() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertTrue(ci.classify("sincronizar esboço") is com.bettertalker.app.data.util.ChatIntent.Intent.Sync)
        assertTrue(ci.classify("atualizar o esboço pela nota") is com.bettertalker.app.data.util.ChatIntent.Intent.Sync)
        // sem regressão
        assertTrue(ci.classify("mostre as seções") is com.bettertalker.app.data.util.ChatIntent.Intent.Sections)
        assertTrue(ci.classify("o que dizem sobre fé?") is com.bettertalker.app.data.util.ChatIntent.Intent.Ask)
    }

    @Test
    fun chatConfirmYesNo() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertTrue(ci.isYes("sim"))
        assertTrue(ci.isYes("Isso, pode adicionar"))
        assertTrue(ci.isYes("ok, fechado"))
        assertTrue(ci.isNo("não"))
        assertTrue(ci.isNo("melhor não, deixa pra depois"))
        assertTrue(ci.isNo("cancela"))
        assertFalse(ci.isYes("não pode ser"))
        assertFalse(ci.isNo("sim, continua"))
    }

    @Test
    fun syncSectionsFromNote() {
        val linked = listOf(
            com.bettertalker.app.data.util.OutlineSection("Abertura", 3, 0, "corpo velho", 0),
            com.bettertalker.app.data.util.OutlineSection("Conclusão", 4, 1, "", 0),
            com.bettertalker.app.data.util.OutlineSection("Sumida", null, 2, "", 0)
        )
        val md = "## Abertura Nova (5 min)\n\ntexto novo\n\n" +
            "## Ideia inserida\n\n## Conclusão\n\nfim\n\n## Tópico Novo\n"
        val (updated, r) = com.bettertalker.app.data.util.syncSections(
            linked, md, setOf("ideia inserida"))
        assertEquals(listOf("Abertura Nova", "Conclusão", "Sumida"), updated.map { it.title })
        assertEquals(5, updated[0].minutes)
        assertEquals("texto novo", updated[0].body)
        assertEquals(listOf("Sumida"), r.missing)
        assertEquals(listOf("Tópico Novo"), r.added)
        assertTrue(r.updated.contains("Abertura Nova"))
    }

    @Test
    fun s34RealPublicWatchtower() {
        // S-34-T N.º 35 integral (texto real do usuário): Sentinela pública tem que sair
        val text = """
N.º 35 É possível viver para sempre? O que você precisa fazer?
FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)
O tempo passa muito rápido e a vida parece muito curta. (Despertai! 08/13 pág. 6)
Temos o desejo de continuar vivendo e nunca morrer. [Leia Eclesiastes 3:11.] (Despertai! 08/13 pág. 8 parág. 1-2)
Deus criou os humanos para viver uma vida perfeita, eterna, na Terra. (Gên 1:26, 31; Sentinela número 3 de 2019 pág. 6-7)
Adão e Eva poderiam ter vivido para sempre se tivessem obedecido a Deus. (Gên 2:16, 17)
COMO A VIDA ETERNA FOI PERDIDA (4 min)
Por conta própria, Adão e Eva escolheram desobedecer a Deus. (Gên 3:6) [Imagem 1]
Eles foram expulsos do jardim do Éden e, com o tempo, morreram. (Gên 3:19, 22, 23; 5:5)
Adão transmitiu o pecado, a imperfeição e a morte a todos os seus descendentes.
[Leia Romanos 5:12.]
Apesar de Adão e Eva terem desobedecido a Deus, o propósito Dele para a humanidade não mudou. (Despertai! 12/08 pág. 7)
COMO É POSSÍVEL TER VIDA ETERNA (9 min)
Os avanços na medicina aumentaram a expectativa de vida. (Sal 90:10; Sentinela número 3 de 2019 pág. 5 parág. 3-4)
Jeová Deus providenciou a solução para o problema do pecado e da morte herdados.
[Leia João 3:16.]
Jesus, de vontade própria, deu sua vida para resgatar os descendentes de Adão. (Mt 20:28; Ro 5:19; Entenda a Bíblia cap. 5 parág. 10-11) [Imagem 2]
Muitos vão viver para sempre em um paraíso na Terra, como é a vontade de Deus. (Sal 37:29)
Milhões vão sobreviver ao fim deste atual sistema. (Ap 7:9, 14; 20:13)
SERÁ QUE A VIDA ETERNA VAI SER AGRADÁVEL? (8 min)
Toda a maldade vai deixar de existir. (Sal 37:10, 11)
Serão eliminadas a doença e a morte. (Is 25:8; 33:24; Ap 21:3, 4)
Deus vai reverter o processo de envelhecimento. (Jó 33:24, 25)
A humanidade vai viver em paz e ter boa qualidade de vida. (Sal 72:7, 16)
E o mais importante, vamos continuar aprendendo sobre Jeová. (Ro 11:33)
VOCÊ VAI VIVER PARA SEMPRE? (4 min)
Deus promete dar vida eterna aos que exercem fé no resgate.
(Jo 3:36; Sentinela número 2 de 2017 pág. 7 parág. 1)
Faça com que a coisa mais importante da sua vida seja obter o conhecimento. (Jo 17:3)
[Siga de perto o material do esboço. Veja o livro Beneficie-se, páginas 52-55, 166-169.]
TEMPO TOTAL: 30 MINUTOS
S-34-T N.º 35 5/20
""".trimIndent()
        val keys = RefDetector.detect(text).map { it.editionKey }.toSet()
        // revistas: Despertai! antigas + Sentinela PÚBLICA (foco do teste)
        assertTrue("keys=$keys", keys.containsAll(setOf("g|2013|8", "g|2008|12", "wp|2019|3", "wp|2017|2")))
        // livros citados por título/sigla
        assertTrue("keys=$keys", keys.contains("book|bhs"))
        assertTrue("keys=$keys", keys.contains("book|be"))
        // textos bíblicos (Gên/Ro/Jo/Sal/Ap/Is/Mt/Eclesiastes/Romanos) não viram publicação
        assertTrue("keys=$keys", keys.none { it.startsWith("book|") && it !in setOf("book|bhs", "book|be") })
        assertEquals(6, keys.size)
    }

    @Test
    fun wpFileNameVariantsMatch() {
        val att = { name: String ->
            AttachmentEntity(
                id = "a-$name", noteId = null, fileName = name,
                kind = "pdf", sizeBytes = 1, appPath = "/x", indexed = true, addedAt = 0
            )
        }
        val ref = RefDetector.DetectedRef(
            "Sentinela número 3 de 2019", RefDetector.Kind.MAGAZINE,
            "wp", "wp|2019|3", "A Sentinela N.º 3 2019 (pública)"
        )
        for (name in listOf("wp19.3.pdf", "wp19_3_T.pdf", "wp2019_3.pdf", "wp201903.pdf")) {
            val hit = RefDetector.matchEdition(ref, listOf(att(name)))
            assertNotNull(name, hit)
            assertEquals(name, "a-$name", hit!!.id)
        }
        // nome real do site: ano+mês da edição (N.º 3/2019 = set-out/2019)
        for (name in listOf("wp_T_201909.pdf", "wp201909.pdf", "wp19_09.pdf")) {
            val hit = RefDetector.matchEdition(ref, listOf(att(name)))
            assertNotNull(name, hit)
            assertEquals(name, "a-$name", hit!!.id)
        }
        // mês errado não casa (maio/2019 não é o N.º 3)
        assertNull(
            RefDetector.matchEdition(ref, listOf(att("wp_T_201905.pdf")))
        )
    }

    @Test
    fun wpIssueMonthsMapping() {
        // 2016-2017: bimestral (N.º 5/2017 = set-out)
        assertEquals(listOf("09", "10"), RefDetector.wpIssueMonths(2017, 5))
        assertEquals(listOf("01", "02"), RefDetector.wpIssueMonths(2016, 1))
        // 2018-2021: trimestral (N.º 3/2019 = set-out; N.º 1 = jan-fev)
        assertEquals(listOf("09", "10"), RefDetector.wpIssueMonths(2019, 3))
        assertEquals(listOf("01", "02"), RefDetector.wpIssueMonths(2018, 1))
        // 2022+: anual, mês do arquivo varia
        assertTrue(RefDetector.wpIssueMonths(2022, 1).contains("01"))
        // fora do calendário conhecido: sem candidatos
        assertTrue(RefDetector.wpIssueMonths(2015, 1).isEmpty())
        assertTrue(RefDetector.wpIssueMonths(2019, 4).isEmpty())
    }

    @Test
    fun contentHitsExcludesGuide() {
        val mkHit = { src: String ->
            com.bettertalker.app.data.repo.ScopedHit(
                com.bettertalker.app.data.db.PassageEntity("p-$src", "a", "texto", "texto"),
                src
            )
        }
        val guide = mkHit("Beneficie-se da Escola do Ministério Teocrático")
        val content = mkHit("A Sentinela N.º 3 2019 (pública)")
        assertEquals(listOf(content), com.bettertalker.app.data.repo.contentHits(listOf(guide, content)))
        assertTrue(com.bettertalker.app.data.repo.contentHits(listOf(guide)).isEmpty())
    }

    @Test
    fun composeDraftSelectsAndCites() {
        val mkHit = { src: String, text: String ->
            com.bettertalker.app.data.repo.ScopedHit(
                com.bettertalker.app.data.db.PassageEntity("p-$src-$text", "a", text, text.lowercase()),
                src
            )
        }
        val long1 = "Jeová deseja que todos vivam para sempre em paz na terra prometida por Deus"
        val dup = "Jeová deseja que todos vivam para sempre em paz na terra prometida"
        val long2 = "A Bíblia promete que a morte será eliminada para sempre no futuro próximo"
        val hits = listOf(
            mkHit("Revista", "$long1. Frase curta."),
            mkHit("Livro", "$dup. Outra frase longa sobre a esperança da vida eterna aqui."),
            mkHit("Revista", long2)
        )
        val out = com.bettertalker.app.data.repo.composeDraft(hits, maxSentences = 5)
        // dedup: long1 e dup colapsam; curta fora; fontes anexadas
        assertEquals(3, out.size)
        assertTrue(out.all { it.text.length >= 40 && it.source.isNotBlank() })
        assertEquals("Revista", out[0].source)
        assertTrue(com.bettertalker.app.data.repo.composeDraft(emptyList()).isEmpty())
        assertEquals(2, com.bettertalker.app.data.repo.composeDraft(hits, maxSentences = 2).size)
    }

    @Test
    fun chatDevelopVerbs() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertTrue(ci.hasDevelopVerbs("desenvolva a introdução"))
        assertTrue(ci.hasDevelopVerbs("escreva sobre fé"))
        assertTrue(ci.hasDevelopVerbs("redija a conclusão"))
        assertFalse(ci.hasDevelopVerbs("ideias para a conclusão"))
        assertFalse(ci.hasDevelopVerbs("o que dizem sobre fé?"))
    }

    @Test
    fun chatIntentDevelopRouting() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        val secs = listOf(
            com.bettertalker.app.data.util.ChatIntent.SectionRef("Introdução"),
            com.bettertalker.app.data.util.ChatIntent.SectionRef("Conclusão")
        )
        val d = ci.classify("desenvolva a introdução", secs)
        assertTrue(d is com.bettertalker.app.data.util.ChatIntent.Intent.Develop)
        d as com.bettertalker.app.data.util.ChatIntent.Intent.Develop
        assertEquals("Introdução", d.sectionHint)
        val help = ci.classify("me ajude com a conclusão", secs)
        assertTrue(help is com.bettertalker.app.data.util.ChatIntent.Intent.Develop &&
            (help as com.bettertalker.app.data.util.ChatIntent.Intent.Develop).sectionHint == "Conclusão")
        val bare = ci.classify("escreva sobre a fé", secs)
        assertTrue(bare is com.bettertalker.app.data.util.ChatIntent.Intent.Develop)
        // sem regressão: ideias continuam ideias, resto intacto
        val id = ci.classify("ideias para a conclusão", secs)
        assertTrue(id is com.bettertalker.app.data.util.ChatIntent.Intent.Ideas)
        assertTrue(ci.classify("me ajude") is com.bettertalker.app.data.util.ChatIntent.Intent.Ideas)
        assertTrue(ci.classify("exemplo para a conclusão", secs) is com.bettertalker.app.data.util.ChatIntent.Intent.Example)
        assertTrue(ci.classify("insere a segunda") is com.bettertalker.app.data.util.ChatIntent.Intent.Insert)
        assertTrue(ci.classify("o que dizem sobre fé?") is com.bettertalker.app.data.util.ChatIntent.Intent.Ask)
    }

    @Test
    fun chatAckGoesToHelp() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        assertTrue(ci.isAck("sim"))
        assertTrue(ci.isAck("ok, entendi"))
        assertTrue(ci.isAck("beleza"))
        assertFalse(ci.isAck("o que dizem sobre fé?"))
        assertFalse(ci.isAck("sim, gera para a conclusão"))
        assertTrue(ci.classify("sim") is com.bettertalker.app.data.util.ChatIntent.Intent.Help)
        assertTrue(ci.classify("ok") is com.bettertalker.app.data.util.ChatIntent.Intent.Help)
        // tópicos curtos continuam pergunta
        assertTrue(ci.classify("fé") is com.bettertalker.app.data.util.ChatIntent.Intent.Ask)
        assertTrue(ci.classify("oi") is com.bettertalker.app.data.util.ChatIntent.Intent.Help)
        assertTrue(ci.classify("obrigado!") is com.bettertalker.app.data.util.ChatIntent.Intent.Thanks)
    }

    @Test
    fun chatDismissKeepsNumbers() {
        // dispensar o cartão 1 reescreve o payload: "insere a 1" pega o próximo
        val codec = com.bettertalker.app.data.util.ChatCodec
        val cards = listOf(
            com.bettertalker.app.data.repo.IdeaCard("A", "ba", "sa", "", "src", "Sec", ""),
            com.bettertalker.app.data.repo.IdeaCard("B", "bb", "sb", "", "src", "Sec", "")
        )
        val payload = codec.escMap(mapOf("section" to "Sec", "cards" to codec.cardsToJson(cards)))
        val rest = codec.cardsFromJson(codec.unescMap(payload)["cards"].orEmpty())
            .toMutableList().also { it.removeAt(0) }
        val payload2 = codec.escMap(mapOf("section" to "Sec", "cards" to codec.cardsToJson(rest)))
        val back = codec.cardsFromJson(codec.unescMap(payload2)["cards"].orEmpty())
        assertEquals(1, back.size)
        assertEquals("B", back[0].title)
    }

    @Test
    fun unionRefsDedupes() {
        val a = RefDetector.DetectedRef("x", RefDetector.Kind.MAGAZINE, "wp", "wp|2019|3", "X")
        val b = RefDetector.DetectedRef("y", RefDetector.Kind.MAGAZINE, "wp", "wp|2019|3", "Y")
        val c = RefDetector.DetectedRef("z", RefDetector.Kind.BOOK, "lff", "book|lff", "Z")
        val u = RefDetector.unionRefs(listOf(a, c), listOf(b))
        assertEquals(listOf("wp|2019|3", "book|lff"), u.map { it.editionKey })
        assertTrue(RefDetector.unionRefs(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun matchSectionNumber() {
        val ci = com.bettertalker.app.data.util.ChatIntent
        val titles = listOf("Abertura", "Meio", "Conclusão")
        assertEquals("Abertura", ci.matchSectionNumber("desenvolva parte 1", titles))
        assertEquals("Meio", ci.matchSectionNumber("ideias para a seção 2", titles))
        assertEquals("Conclusão", ci.matchSectionNumber("tópico 3", titles))
        assertNull(ci.matchSectionNumber("desenvolva a introdução", titles))
        assertNull(ci.matchSectionNumber("parte 9", titles))
        assertNull(ci.matchSectionNumber("o que dizem sobre fé?", titles))
        // número vira dica nas três ações
        val d = ci.classify("desenvolva parte 1", titles.map { com.bettertalker.app.data.util.ChatIntent.SectionRef(it) })
        assertTrue(d is com.bettertalker.app.data.util.ChatIntent.Intent.Develop &&
            (d as com.bettertalker.app.data.util.ChatIntent.Intent.Develop).sectionHint == "Abertura")
        val e = ci.classify("exemplo da parte 3", titles.map { com.bettertalker.app.data.util.ChatIntent.SectionRef(it) })
        assertTrue(e is com.bettertalker.app.data.util.ChatIntent.Intent.Example)
        val i = ci.classify("ideias para o tópico 2", titles.map { com.bettertalker.app.data.util.ChatIntent.SectionRef(it) })
        assertTrue(i is com.bettertalker.app.data.util.ChatIntent.Intent.Ideas &&
            (i as com.bettertalker.app.data.util.ChatIntent.Intent.Ideas).sectionHint == "Meio")
    }

    @Test
    fun detectBibleVerses() {
        val b1 = RefDetector.detectBible("o que diz Gênesis 1:26?")
        assertEquals(1, b1.size)
        assertEquals("genesis", b1[0].bookNorm)
        assertEquals("Gênesis", b1[0].label)
        assertEquals(1, b1[0].chapter)
        assertEquals(26, b1[0].verse)
        val b2 = RefDetector.detectBible("conforme 1 João 4:8 e Jo 3:16")
        assertEquals(setOf("1 João 4:8", "João 3:16"),
            b2.map { "${it.label} ${it.chapter}:${it.verse}" }.toSet())
        // "às 19:30" não é versículo; sigla desconhecida também não
        assertTrue(RefDetector.detectBible("a reunião é às 19:30").isEmpty())
        assertTrue(RefDetector.detectBible("ver XYZ 1:1 sobre isso").isEmpty())
        // dedupe
        assertEquals(1, RefDetector.detectBible("Jo 3:16 e João 3:16").size)
    }

    @Test
    fun verseRefMatchesNumbers() {
        fun f(norm: String, book: String, chapter: Int, verse: Int) =
            com.bettertalker.app.data.repo.verseRefMatches(norm, book, chapter, verse)
        assertTrue(f("no principio deus criou os ceus e a terra gen 1 26", "gen", 1, 26))
        assertFalse(f("no principio deus criou os ceus e a terra gen 1 26", "gen", 1, 27))
        assertFalse(f("outro texto sem o livro", "gen", 1, 26))
        // "gen 126" grudado não vale como cap 12 v 6 (sem fronteira não há match exato)
        assertFalse(f("texto gen 126 aqui", "gen", 12, 6))
    }
}
