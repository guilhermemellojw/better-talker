package com.bettertalker.app

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.DocExtractors
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.PastedOutlineAnalyzer
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.headingsOf
import com.bettertalker.app.data.util.insertUnder
import org.junit.Assert.assertEquals
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
}
