package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
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
}
