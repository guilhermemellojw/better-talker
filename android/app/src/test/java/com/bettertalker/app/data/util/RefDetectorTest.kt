package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefDetectorTest {

    @Test
    fun detectBible_tnmPr_works() {
        val ref = RefDetector.detectBible("Pr 3:5").single()
        assertEquals("Provérbios", ref.label)
        assertEquals(3, ref.chapter)
        assertEquals(5, ref.verse)
    }

    @Test
    fun detectBible_tnmHe_works() {
        assertEquals("Hebreus", RefDetector.detectBible("He 11:1").single().label)
    }

    @Test
    fun detectBible_tnmNaum_works() {
        assertEquals("Naum", RefDetector.detectBible("Na 2:1").single().label)
        assertEquals("Naum", RefDetector.detectBible("Nau 2:1").single().label)
        assertEquals("Naum", RefDetector.detectBible("Naum 2:1").single().label)
    }

    @Test
    fun detectBible_tnmZa_works() {
        assertEquals("Zacarias", RefDetector.detectBible("Za 1:1").single().label)
    }

    @Test
    fun detectBible_tnm1Te_works() {
        // Sem espaço entre número e livro (forma TNM): "1Te".
        val ref = RefDetector.detectBible("1Te 4:16").single()
        assertEquals("1 Tessalonicenses", ref.label)
    }

    @Test
    fun detectBible_tnmFlm_works() {
        assertEquals("Filemom", RefDetector.detectBible("Flm 1:6").single().label)
    }

    @Test
    fun detectBible_tnmRu_works() {
        assertEquals("Rute", RefDetector.detectBible("Ru 1:1").single().label)
    }

    @Test
    fun detectBible_tnmEsd_works() {
        assertEquals("Esdras", RefDetector.detectBible("Esd 1:1").single().label)
    }

    // ---------- T1 (refs): ponto na abreviação, aliases e listas ----------

    @Test
    fun detectBible_aceitaPontoNaAbreviacao() {
        val ref = RefDetector.detectBible("(Jer. 41:1)").single()
        assertEquals("Jeremias", ref.label)
        assertEquals(41, ref.chapter)
        assertEquals(1, ref.verse)
    }

    @Test
    fun detectBible_aliasJerECor() {
        assertEquals("Jeremias", RefDetector.detectBible("Jer 41:1").single().label)
        assertEquals("1 Coríntios", RefDetector.detectBible("(1 Cor. 15:3)").single().label)
    }

    @Test
    fun detectBible_listaDeVersiculos() {
        val refs = RefDetector.detectBible("(Jer. 41:1, 2; it qualquer coisa)")
        assertEquals(listOf(1, 2), refs.map { it.verse })
        assertEquals(listOf("Jeremias", "Jeremias"), refs.map { it.label })
    }

    @Test
    fun detectBible_faixaDeVersiculos() {
        val refs = RefDetector.detectBible("(Gên 3:6-8)")
        assertEquals(listOf(6, 7, 8), refs.map { it.verse })
    }

    @Test
    fun detectBible_listaNaoEngoleOutroLivro() {
        // "41:1, 2 Reis 25:22" — o "2" pertence a outro livro, não é versículo.
        val refs = RefDetector.detectBible("(Jer. 41:1, 2 Reis 25:22)")
        assertEquals(2, refs.size)
        assertEquals(1, refs.first { it.label == "Jeremias" }.verse)
        assertEquals("2 Reis", refs.first { it.label == "2 Reis" }.label)
    }

    @Test
    fun detectBible_linhaRealDoEsboco() {
        // Exemplo do dono: Jer. 41:1, 2 + it "Gedalias" n.° 4 (o it é T2).
        val refs = RefDetector.detectBible(
            "Jeová não salvou a vida de Gedalias, embora ele fosse um homem que " +
                "temia a Jeová. (Jer. 41:1, 2; it \"Gedalias\" n.° 4)"
        )
        assertEquals(listOf("Jeremias" to 1, "Jeremias" to 2), refs.map { it.label to it.verse })
    }

    @Test
    fun detectBible_novoCapituloNoMesmoLivro() {
        // Formato real do S-34: "(Gên 3:19, 22, 23; 5:5)".
        val refs = RefDetector.detectBible("(Gên 3:19, 22, 23; 5:5)")
        assertEquals(
            listOf(3 to 19, 3 to 22, 3 to 23, 5 to 5),
            refs.map { it.chapter to it.verse },
        )
    }

    @Test
    fun detectBible_outroLivroNaoEhCapitulo() {
        // "(Mt 20:28; Ro 5:19)" — "Ro 5:19" é outro livro, não capítulo de Mt.
        val refs = RefDetector.detectBible("(Mt 20:28; Ro 5:19)")
        assertEquals(
            listOf("Mateus" to 20 to 28, "Romanos" to 5 to 19),
            refs.map { it.label to it.chapter to it.verse },
        )
    }

    /**
     * DÉBITO TÉCNICO (3.2.3a-fix): "Jó" e "João" normalizam para a mesma
     * chave ("jo"), e o mapa resolve para João. Este teste documenta o
     * comportamento conhecido — corrigir exige matcher sensível ao texto cru.
     */
    @Test
    fun detectBible_jobVsJohn_knownLimitation() {
        val ref = RefDetector.detectBible("Jó 14:1").single()
        assertEquals("João", ref.label)
    }

    @Test
    fun detectBible_fullNameStillWorks() {
        assertEquals("Gênesis", RefDetector.detectBible("Gênesis 3:6").single().label)
        assertEquals("Gênesis", RefDetector.detectBible("Gên 3:6").single().label)
    }

    // ---------- 3.2.3a-fix4: Sentinela nomeada por data (S-31-T antigo) ----------

    @Test
    fun detect_sentinelaDDMMYY_detects() {
        val ref = RefDetector.detect("Sentinela 01/04/09 pág. 5-6")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("w", ref.pubKey)
        assertEquals("w|2009|4|1", ref.editionKey)
        assertTrue(ref.label.contains("pág. 5-6"))
    }

    @Test
    fun detect_sentinelaQuinzenalAntiga_detects() {
        val ref = RefDetector.detect("Sentinela 15/03/04 pág. 6")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("w|2004|3|15", ref.editionKey)
    }

    @Test
    fun detect_sentinelaMMYY_detects() {
        val ref = RefDetector.detect("Sentinela 01/20 pág. 22 parág. 8-9")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("w|2020|1", ref.editionKey)
    }

    @Test
    fun detect_sentinelaQuinzenalAntigaComAno2Digitos_detects() {
        val ref = RefDetector.detect("Sentinela 15/03/91 pág. 21 parág. 2")
            .single { it.kind == RefDetector.Kind.MAGAZINE }
        assertEquals("w|1991|3|15", ref.editionKey)
    }

    @Test
    fun detect_modernFormatsStillWork() {
        assertEquals("w|2021|8", RefDetector.detect("(w21.08 18 § 13)").single().editionKey)
        assertEquals("g|2007|6", RefDetector.detect("(g 6/07 8 § 2)").single().editionKey)
        assertEquals("w|1994|8|1", RefDetector.detect("(w94 1/8 3)").single().editionKey)
    }

    // ---------- 3.2.3a-fix4c: colisão "Re 15:3" × livro "re" ----------

    @Test
    fun detect_reCapVers_notDetectedAsBook() {
        val refs = RefDetector.detect("(Re 15:3b)")
        assertTrue("não pode virar livro re: $refs", refs.none { it.pubKey == "re" })
        // e o detectBible captura como Apocalipse (com sufixo de letra)
        val br = RefDetector.detectBible("Re 15:3b").single()
        assertEquals("Apocalipse", br.label)
        assertEquals(15, br.chapter)
        assertEquals(3, br.verse)
    }

    @Test
    fun detect_reWithPage_stillDetected() {
        assertEquals("re", RefDetector.detect("(re 292)").single().pubKey)
    }

    @Test
    fun detect_reRange_stillDetected() {
        assertEquals("re", RefDetector.detect("(re 285-6)").single().pubKey)
    }

    @Test
    fun detect_reMultiPage_stillDetected() {
        assertEquals("re", RefDetector.detect("(re 292, 300)").single().pubKey)
    }

    // ---------- T2 (refs): estudo por artigo + parágrafo ----------

    @Test
    fun detect_artigoComParagrafo_works() {
        val ref = RefDetector.detect("(it \"Gedalias\" n.° 4)").single()
        assertEquals("it", ref.pubKey)
        assertEquals("Gedalias", ref.article)
        assertEquals(4, ref.paragraph)
        assertEquals("book|it", ref.editionKey.substringBefore("|a:"))
    }

    @Test
    fun detect_artigo_variantesDeAspasENumero() {
        assertEquals(4, RefDetector.detect("it “Gedalias” n.º 4").single().paragraph)
        assertEquals(4, RefDetector.detect("it ‘Gedalias’ n.º 4").single().paragraph)
        val noQuotes = RefDetector.detect("it Gedalias n.° 4").single()
        assertEquals("Gedalias", noQuotes.article)
        assertEquals(4, noQuotes.paragraph)
        val rsg = RefDetector.detect("rsg \"Mispá, Mispé\" § 5").single()
        assertEquals("rsg", rsg.pubKey)
        assertEquals("Mispá, Mispé", rsg.article)
        assertEquals(5, rsg.paragraph)
    }

    @Test
    fun detect_licaoCapituloEstudo_comParagrafo() {
        val lmd = RefDetector.detect("(lmd lição 3 § 4)").single()
        assertEquals("lmd", lmd.pubKey)
        assertEquals("lição 3", lmd.chapter)
        assertEquals(4, lmd.paragraph)

        val rr = RefDetector.detect("(rr cap. 5 § 2)").single()
        assertEquals("cap. 5", rr.chapter)
        assertEquals(2, rr.paragraph)

        val jr = RefDetector.detect("(jr 27 § 22)").single()
        assertEquals("estudo 27", jr.chapter)
        assertEquals(22, jr.paragraph)
    }

    @Test
    fun detect_pagina_works() {
        val be = RefDetector.detect("(be pág. 52)").single()
        assertEquals("be", be.pubKey)
        assertEquals(52, be.page)
    }

    @Test
    fun detect_artigo_naoDisparaEmTextoComum() {
        assertEquals(emptyList<RefDetector.DetectedRef>(), RefDetector.detect("vamos fazer it agora mesmo"))
    }

    @Test
    fun chapterOf_entendeNumeroDeParagrafo() {
        assertEquals("paragrafo", RefDetector.chapterOf(" n.° 4")?.kind)
        assertEquals(4, RefDetector.chapterOf(" n.° 4")?.number)
        assertEquals(4, RefDetector.chapterOf(" n.º 4")?.number)
        assertEquals(4, RefDetector.chapterOf("número 4")?.number)
    }

    @Test
    fun detectedJson_roundTrip_comArtigoEParagrafo() {
        val refs = RefDetector.detect("(it \"Gedalias\" n.° 4)")
        val back = RefDetector.detectedFromJson(RefDetector.detectedToJson(refs))
        assertEquals(1, back.size)
        assertEquals("Gedalias", back.single().article)
        assertEquals(4, back.single().paragraph)
    }

    @Test
    fun detectedFromJson_aceitaFormatoAntigo() {
        val old = """[{"r":"it 813","k":"BOOK","p":"it","e":"book|it","l":"Estudo Perspicaz (pág. 813)"}]"""
        val back = RefDetector.detectedFromJson(old)
        assertEquals(1, back.size)
        assertEquals("it", back.single().pubKey)
        assertNull(back.single().article)
        assertNull(back.single().paragraph)
    }
}
