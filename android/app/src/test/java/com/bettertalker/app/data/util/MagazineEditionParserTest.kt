package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2.5 — fixtures de Sentinela/Despertai sobre o parser puro. */
class MagazineEditionParserTest {

    private fun check(
        raw: String,
        key: String,
        kind: MagazineKind,
        family: MagazineFamily,
        page: Int? = null,
        pageEnd: Int? = null,
        language: MagazineLanguage = MagazineLanguage.PT,
        confidence: MagazineConfidence = MagazineConfidence.EXACT,
        bound: Boolean = false,
    ): MagazineEdition {
        val e = MagazineEditionParser.parse(raw)
        assertNotNull("não parseou: $raw", e)
        checkNotNull(e)
        assertEquals("key de $raw", key, e.editionKey())
        assertEquals("family de $raw", family, e.family)
        assertEquals("kind de $raw", kind, e.kind)
        assertEquals("page de $raw", page, e.page)
        assertEquals("pageEnd de $raw", pageEnd, e.pageEnd)
        assertEquals("language de $raw", language, e.language)
        assertEquals("confidence de $raw", confidence, e.confidence)
        assertEquals("bound de $raw", bound, e.boundVolume)
        return e
    }

    private fun kindForDay(day: Int): MagazineKind = when (day) {
        1 -> MagazineKind.PUBLIC
        15 -> MagazineKind.STUDY
        else -> MagazineKind.UNKNOWN
    }

    private fun checkOldW(raw: String, key: String) {
        val day = key.substringAfterLast('|').toInt()
        check(
            raw, key, kindForDay(day), MagazineFamily.WATCHTOWER,
        )
    }

    private fun checkNamedSentinela(raw: String, key: String) {
        val parts = key.split('|')
        val kind = when {
            key.startsWith("wp|") -> MagazineKind.PUBLIC
            parts.size >= 4 -> kindForDay(parts[3].toInt())
            else -> MagazineKind.STUDY
        }
        check(raw, key, kind, MagazineFamily.WATCHTOWER)
    }

    @Test
    fun sentinelaModernaEstudo() {
        listOf(
            "w25.07" to "w|2025|7",
            "w22.03" to "w|2022|3",
            "w18.11" to "w|2018|11",
            "w19.03" to "w|2019|3",
            "w19.09" to "w|2019|9",
            "w20.04" to "w|2020|4",
            "w20.10" to "w|2020|10",
            "w22.05" to "w|2022|5",
            "w22.08" to "w|2022|8",
            "w16.09" to "w|2016|9",
            "w17.12" to "w|2017|12",
            "w18.02" to "w|2018|2",
            "w18.06" to "w|2018|6",
            "w18.12" to "w|2018|12",
            "w19.12" to "w|2019|12",
            "w24/12" to "w|2024|12",
        ).forEach { (raw, key) ->
            check(raw, key, MagazineKind.STUDY, MagazineFamily.WATCHTOWER)
        }
    }

    @Test
    fun sentinelaPublicaNumerada() {
        listOf(
            "wp20.1" to "wp|2020|1",
            "wp25.07" to "wp|2025|7",
            "wp19.3" to "wp|2019|3",
            "wp24 N.º 1" to "wp|2024|1",
            "Sentinela (público) N.° 1 de 2016" to "wp|2016|1",
            "Sentinela (público) 05/16" to "wp|2016|5",
            "Sentinela número 3 de 2019" to "wp|2019|3",
            "A Sentinela, n.º 2 de 2017" to "wp|2017|2",
            "Sentinela (estudo) 05/16" to "w|2016|5",
        ).forEach { (raw, key) ->
            check(
                raw, key,
                if (key.startsWith("wp|")) MagazineKind.PUBLIC else MagazineKind.STUDY,
                MagazineFamily.WATCHTOWER,
            )
        }
    }

    @Test
    fun sentinelaAntigaDiaMes() {
        listOf(
            "w14 15/5" to "w|2014|5|15",
            "w11 1/7" to "w|2011|7|1",
            "w94 1/8" to "w|1994|8|1",
            "w92 1/5" to "w|1992|5|1",
            "w92 15/5" to "w|1992|5|15",
            "w73 1/3" to "w|1973|3|1",
            "w90 15/3" to "w|1990|3|15",
            "w86 1/10" to "w|1986|10|1",
            "w83 1/7" to "w|1983|7|1",
            "w81 1/7" to "w|1981|7|1",
            "w76 15/3" to "w|1976|3|15",
            "w91 1/8" to "w|1991|8|1",
            "w82 1/5" to "w|1982|5|1",
            "w89 1/6" to "w|1989|6|1",
            "w89 15/1" to "w|1989|1|15",
            "w92 15/1" to "w|1992|1|15",
            "w89 15/8" to "w|1989|8|15",
            "w94 1/4" to "w|1994|4|1",
            "w93 15/2" to "w|1993|2|15",
            "w00 1/4" to "w|2000|4|1",
            "w01 1/8" to "w|2001|8|1",
            "w02 1/3" to "w|2002|3|1",
            "w02 15/7" to "w|2002|7|15",
            "w03 1/7" to "w|2003|7|1",
            "w03 15/3" to "w|2003|3|15",
            "w03 15/5" to "w|2003|5|15",
            "w03 15/12" to "w|2003|12|15",
            "w04 1/11" to "w|2004|11|1",
            "w04 15/12" to "w|2004|12|15",
            "w04 15/7" to "w|2004|7|15",
            "w05 15/3" to "w|2005|3|15",
            "w05 15/11" to "w|2005|11|15",
            "w06 1/12" to "w|2006|12|1",
            "w06 15/9" to "w|2006|9|15",
            "w06 15/12" to "w|2006|12|15",
            "w07 1/4" to "w|2007|4|1",
            "w07 15/5" to "w|2007|5|15",
            "w07 15/9" to "w|2007|9|15",
            "w07 15/10" to "w|2007|10|15",
            "w08 1/5" to "w|2008|5|1",
            "w08 1/6" to "w|2008|6|1",
            "w08 1/9" to "w|2008|9|1",
            "w08 15/3" to "w|2008|3|15",
            "w08 15/8" to "w|2008|8|15",
            "w08 15/11" to "w|2008|11|15",
            "w09 1/8" to "w|2009|8|1",
            "w09 15/5" to "w|2009|5|15",
            "w09 15/7" to "w|2009|7|15",
            "w09 15/10" to "w|2009|10|15",
            "w10 1/2" to "w|2010|2|1",
            "w10 1/10" to "w|2010|10|1",
            "w10 15/4" to "w|2010|4|15",
            "w10 15/5" to "w|2010|5|15",
            "w10 15/7" to "w|2010|7|15",
            "w10 15/8" to "w|2010|8|15",
            "w11 1/11" to "w|2011|11|1",
            "w11 15/2" to "w|2011|2|15",
            "w11 15/6" to "w|2011|6|15",
            "w11 15/9" to "w|2011|9|15",
            "w11 15/12" to "w|2011|12|15",
            "w12 1/5" to "w|2012|5|1",
            "w12 15/4" to "w|2012|4|15",
            "w12 15/7" to "w|2012|7|15",
            "w12 15/11" to "w|2012|11|15",
            "w13 15/2" to "w|2013|2|15",
            "w13 15/3" to "w|2013|3|15",
            "w13 15/4" to "w|2013|4|15",
            "w13 15/10" to "w|2013|10|15",
            "w14 15/1" to "w|2014|1|15",
            "w14 15/2" to "w|2014|2|15",
            "w14 15/4" to "w|2014|4|15",
            "w15 15/2" to "w|2015|2|15",
            "w15 15/4" to "w|2015|4|15",
            "w15 15/5" to "w|2015|5|15",
            "w15 15/7" to "w|2015|7|15",
            "w15 15/9" to "w|2015|9|15",
            "w15 15/11" to "w|2015|11|15",
            "w15 15/12" to "w|2015|12|15",
            "w45 1/8" to "w|1945|8|1",
        ).forEach { (raw, key) -> checkOldW(raw, key) }
    }

    @Test
    fun sentinelaPorExtenso() {
        listOf(
            "Sentinela 15/05/14" to "w|2014|5|15",
            "Sentinela 01/06/15" to "w|2015|6|1",
            "Sentinela 15/08/13" to "w|2013|8|15",
            "Sentinela 15/01/11" to "w|2011|1|15",
            "Sentinela 15/03/03" to "w|2003|3|15",
            "Sentinela 15/12/12" to "w|2012|12|15",
            "Sentinela 15/07/09" to "w|2009|7|15",
            "Sentinela 15/01/10" to "w|2010|1|15",
            "Sentinela 01/09/11" to "w|2011|9|1",
            "Sentinela 15/10/12" to "w|2012|10|15",
            "Sentinela 15/05/12" to "w|2012|5|15",
            "Sentinela 01/06/89" to "w|1989|6|1",
            "Sentinela 15/01/15" to "w|2015|1|15",
            "Sentinela 15/12/08" to "w|2008|12|15",
            "Sentinela 15/05/15" to "w|2015|5|15",
            "Sentinela 01/04/07" to "w|2007|4|1",
            "Sentinela 15/08/12" to "w|2012|8|15",
            "Sentinela 01/06/14" to "w|2014|6|1",
            "Sentinela 01/01/10" to "w|2010|1|1",
            "Sentinela 15/03/06" to "w|2006|3|15",
            "Sentinela 15/07/03" to "w|2003|7|15",
            "Sentinela 15/11/15" to "w|2015|11|15",
            "Sentinela 15/04/14" to "w|2014|4|15",
            "Sentinela 15/03/14" to "w|2014|3|15",
            "Sentinela 15/11/09" to "w|2009|11|15",
            "Sentinela 15/04/02" to "w|2002|4|15",
            "Sentinela 15/05/09" to "w|2009|5|15",
            "Sentinela 01/08/01" to "w|2001|8|1",
            "Sentinela 15/05/11" to "w|2011|5|15",
            "Sentinela 01/04/06" to "w|2006|4|1",
            "Sentinela 15/05/08" to "w|2008|5|15",
            "Sentinela 15/02/13" to "w|2013|2|15",
            "Sentinela 15/07/15" to "w|2015|7|15",
            "Sentinela 15/10/15" to "w|2015|10|15",
            "Sentinela 15/06/12" to "w|2012|6|15",
            "Sentinela 15/07/06" to "w|2006|7|15",
            "Sentinela 15/06/15" to "w|2015|6|15",
            "Sentinela 01/10/14" to "w|2014|10|1",
            "Sentinela 07/21" to "w|2021|7",
            "Sentinela 08/20" to "w|2020|8",
        ).forEach { (raw, key) -> checkNamedSentinela(raw, key) }
    }

    @Test
    fun despertaiMesAno() {
        listOf(
            "Despertai! 08/13" to "g|2013|8",
            "Despertai! 12/08" to "g|2008|12",
            "Despertai! 01/12" to "g|2012|1",
            "Despertai! 10/10" to "g|2010|10",
            "Despertai!, 8/2013" to "g|2013|8",
            "Despertai! 08/15" to "g|2015|8",
            "Despertai! 11/11" to "g|2011|11",
            "Despertai! 12/07" to "g|2007|12",
            "Despertai! 05/10" to "g|2010|5",
            "Despertai! 05/09" to "g|2009|5",
            "Despertai! 01/13" to "g|2013|1",
            "Despertai! 02/10" to "g|2010|2",
            "Despertai! de maio de 2011" to "g|2011|5",
            "g 6/14" to "g|2014|6",
            "g 10/12" to "g|2012|10",
            "g 3/15" to "g|2015|3",
            "g 12/09" to "g|2009|12",
            "g 1/15" to "g|2015|1",
            "g 11/11" to "g|2011|11",
            "g 11/07" to "g|2007|11",
            "g 7/12" to "g|2012|7",
            "g 2/11" to "g|2011|2",
            "g 10/11" to "g|2011|10",
            "g 8/07" to "g|2007|8",
            "g 2/13" to "g|2013|2",
            "g 6/15" to "g|2015|6",
            "g 5/09" to "g|2009|5",
            "g 9/13" to "g|2013|9",
            "g 10/10" to "g|2010|10",
            "g 11/10" to "g|2010|11",
            "g 1/24" to "g|2024|1",
        ).forEach { (raw, key) ->
            check(raw, key, MagazineKind.PUBLIC, MagazineFamily.AWAKE)
        }
    }

    @Test
    fun despertaiIssueEDiaMes() {
        listOf(
            "Despertai! N.º 3 de 2017" to "gn|2017|3",
            "Despertai! N.º 1 2024" to "gn|2024|1",
            "g16.1" to "gn|2016|1",
            "g18.2" to "gn|2018|2",
            "g19.03" to "gn|2019|3",
        ).forEach { (raw, key) ->
            val e = check(raw, key, MagazineKind.PUBLIC, MagazineFamily.AWAKE)
            assertTrue("issue ausente: $raw", e.isIssue)
            assertEquals("display de $raw", "g|${e.year} · issue ${e.issue}", e.displayKey())
        }

        listOf(
            "Despertai! 08/07/02" to "g|2002|7|8",
            "g92 8/10" to "g|1992|10|8",
            "g93 22/1" to "g|1993|1|22",
            "g90 8/5" to "g|1990|5|8",
            "g92 22/7" to "g|1992|7|22",
            "g93 8/1" to "g|1993|1|8",
            "g90 22/10" to "g|1990|10|22",
            "g92 22/3" to "g|1992|3|22",
            "g94 8/4" to "g|1994|4|8",
            "g91 8/8" to "g|1991|8|8",
            "g87 8/4" to "g|1987|4|8",
            "g89 22/3" to "g|1989|3|22",
            "g45 8/1" to "g|1945|1|8",
        ).forEach { (raw, key) ->
            check(raw, key, MagazineKind.PUBLIC, MagazineFamily.AWAKE)
        }
    }

    @Test
    fun casosEspeciaisPaginaFaixaIdiomaEVolume() {
        check(
            "w11 1/7 10", "w|2011|7|1", MagazineKind.PUBLIC,
            MagazineFamily.WATCHTOWER, page = 10,
        )
        check(
            "w25.07 22-24", "w|2025|7", MagazineKind.STUDY,
            MagazineFamily.WATCHTOWER, page = 22, pageEnd = 24,
        )
        check(
            "w92 1/5 26-9", "w|1992|5|1", MagazineKind.PUBLIC,
            MagazineFamily.WATCHTOWER, page = 26, pageEnd = 29,
        )
        check(
            "w82 1/5 22-4", "w|1982|5|1", MagazineKind.PUBLIC,
            MagazineFamily.WATCHTOWER, page = 22, pageEnd = 24,
        )
        check(
            "w90 15/3 26-8", "w|1990|3|15", MagazineKind.STUDY,
            MagazineFamily.WATCHTOWER, page = 26, pageEnd = 28,
        )
        check(
            "w73 1/3 140-1", "w|1973|3|1", MagazineKind.PUBLIC,
            MagazineFamily.WATCHTOWER, page = 140, pageEnd = 141,
        )
        check(
            "g92 22/3 28, em inglês", "g|1992|3|22", MagazineKind.PUBLIC,
            MagazineFamily.AWAKE, page = 28, language = MagazineLanguage.EN,
        )
        val bound = check(
            "w73 652", "w|1973|bound", MagazineKind.UNKNOWN,
            MagazineFamily.WATCHTOWER, page = 652, bound = true,
        )
        assertEquals("w|1973|bound", bound.displayKey())

        check(
            "Sentinela 01/20 pág. 22 parág. 8-9", "w|2020|1", MagazineKind.STUDY,
            MagazineFamily.WATCHTOWER, page = 22,
        )
        check(
            "Sentinela número 3 de 2019 pág. 6-7", "wp|2019|3", MagazineKind.PUBLIC,
            MagazineFamily.WATCHTOWER, page = 6, pageEnd = 7,
        )
        check(
            "g16.1 5", "gn|2016|1", MagazineKind.PUBLIC,
            MagazineFamily.AWAKE, page = 5,
        )
    }

    @Test
    fun ambiguidadeNaoEhEscolhidaEmSilencio() {
        val e = check(
            "g 1/16", "g|2016|1", MagazineKind.PUBLIC, MagazineFamily.AWAKE,
            confidence = MagazineConfidence.AMBIGUOUS,
        )
        assertTrue(e.note.orEmpty().contains("janeiro/2016"))
        assertTrue(e.note.orEmpty().contains("edição 1/2016"))
        assertNull(
            "texto comum não pode virar revista",
            MagazineEditionParser.parse("o ponto diz respeito à vida eterna"),
        )
        assertNull(
            "Gên 1:26 é Bíblia, não revista",
            MagazineEditionParser.parse("Gên 1:26"),
        )
        assertNull(
            "w24.125 não é edição válida",
            MagazineEditionParser.parse("w24.125"),
        )
    }
}
