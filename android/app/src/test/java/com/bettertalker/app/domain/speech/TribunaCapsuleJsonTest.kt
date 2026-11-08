package com.bettertalker.app.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #20-fix-a — cápsula da Tribuna: serialização JSON manual. Puro. */
class TribunaCapsuleJsonTest {

    @Test
    fun roundTrip() {
        val c = TribunaCapsule(
            sectionId = "sec-1",
            mainIdea = "Jeová organiza os céus.",
            bibleRef = "Sal 103:20",
            anchorPhrase = "Ele valoriza muito a ordem",
            transition = "Mas não é só no céu.",
        )
        assertEquals(c, tribunaCapsuleFromJson(c.toJson()))
        assertEquals(listOf(c), tribunaCapsulesFromJson(listOf(c).toJson()))
    }

    @Test
    fun listaVazia() {
        assertEquals("[]", emptyList<TribunaCapsule>().toJson())
        assertTrue(tribunaCapsulesFromJson("[]").isEmpty())
    }

    @Test
    fun jsonCorrompidoNaoCrasha() {
        assertNull(tribunaCapsuleFromJson("{corrompido"))
        assertTrue(tribunaCapsulesFromJson("não é json").isEmpty())
    }

    @Test
    fun camposOpcionaisNulos() {
        val c = TribunaCapsule(sectionId = "s", mainIdea = "i", anchorPhrase = "a")
        val back = tribunaCapsuleFromJson(c.toJson())!!
        assertNull(back.bibleRef)
        assertNull(back.transition)
        assertNull(back.timeMinutes)
        assertTrue(back.cues.isEmpty())
        assertTrue(back.keyTerms.isEmpty())
    }

    @Test
    fun roundTripV42ComCuesTermosETempo() {
        val c = TribunaCapsule(
            sectionId = "sec-1",
            mainIdea = "Jeová organiza os céus.",
            bibleRef = "Sal 103:20",
            anchorPhrase = "Ele valoriza muito a ordem",
            transition = "Mas não é só no céu.",
            timeMinutes = 6,
            keyTerms = listOf("ordem", "céus", "anjos"),
            cues = listOf(
                TribunaCue(
                    kind = TribunaCueKind.HOOK,
                    text = "Você já parou para observar o céu?",
                ),
                TribunaCue(
                    kind = TribunaCueKind.SCRIPTURE,
                    text = "Os anjos obedecem à voz de Jeová.",
                    refs = listOf("Sal 103:20"),
                ),
                TribunaCue(
                    kind = TribunaCueKind.IMAGE,
                    text = "Mostre o gráfico.",
                    imageId = "img-1",
                    imageCaption = "Gráfico da criação",
                ),
            ),
        )
        assertEquals(c, tribunaCapsuleFromJson(c.toJson()))
    }

    @Test
    fun jsonAntigoContinuaLegivelSemMigracao() {
        val old = "{\"sectionId\":\"s\",\"mainIdea\":\"i\",\"bibleRef\":\"Sal 1:1\"," +
            "\"anchorPhrase\":\"a\",\"transition\":\"t\"}"
        val c = tribunaCapsuleFromJson(old)!!
        assertEquals("i", c.mainIdea)
        assertEquals("Sal 1:1", c.bibleRef)
        assertEquals("a", c.anchorPhrase)
        assertEquals("t", c.transition)
        assertNull(c.timeMinutes)
        assertTrue(c.cues.isEmpty())
        assertTrue(c.keyTerms.isEmpty())
        assertEquals(0, c.cueSheetVersion)
    }

    // ---------- v5.1: 15 tipos, versão da folha e tipo desconhecido ----------

    @Test
    fun todosOsQuinzeTiposFazemRoundTrip() {
        assertEquals(15, TribunaCueKind.values().size)
        val cues = TribunaCueKind.values().mapIndexed { index, kind ->
            TribunaCue(
                kind = kind,
                text = "Lembrete $index",
                refs = if (kind == TribunaCueKind.SCRIPTURE) listOf("Sal 103:20") else emptyList(),
            )
        }
        val c = TribunaCapsule(
            sectionId = "s",
            mainIdea = "i",
            anchorPhrase = "a",
            cues = cues,
            cueSheetVersion = 2,
        )
        assertEquals(c, tribunaCapsuleFromJson(c.toJson()))
    }

    @Test
    fun tipoDesconhecidoViraReasoningSemDescartarTextoERefs() {
        val json = "{\"sectionId\":\"s\",\"mainIdea\":\"i\",\"anchorPhrase\":\"a\"," +
            "\"cueSheetVersion\":2,\"cues\":[" +
            "{\"kind\":\"FUTURE_KIND\",\"text\":\"Texto preservado\"," +
            "\"refs\":[\"Sal 1:1\",\"Ef 4:8\"]}]}"
        val c = tribunaCapsuleFromJson(json)!!
        assertEquals(2, c.cueSheetVersion)
        assertEquals(1, c.cues.size)
        assertEquals(TribunaCueKind.REASONING, c.cues[0].kind)
        assertEquals("Texto preservado", c.cues[0].text)
        assertEquals(listOf("Sal 1:1", "Ef 4:8"), c.cues[0].refs)
    }

    @Test
    fun folhaVaziaV2SerializaCuesVazioEVoltaComoMapaVazio() {
        val c = TribunaCapsule(
            sectionId = "s",
            mainIdea = "i",
            anchorPhrase = "a",
            cueSheetVersion = 2,
        )
        val json = c.toJson()
        assertTrue(json.contains("\"cueSheetVersion\":2"))
        assertTrue(json.contains("\"cues\":[]"))
        val back = tribunaCapsuleFromJson(json)!!
        assertEquals(2, back.cueSheetVersion)
        assertTrue(back.cues.isEmpty())
    }

    @Test
    fun capsulaLegadaNaoEscreveVersaoNemCues() {
        val c = TribunaCapsule(sectionId = "s", mainIdea = "i", anchorPhrase = "a")
        val json = c.toJson()
        assertTrue(!json.contains("cueSheetVersion"))
        assertTrue(!json.contains("\"cues\""))
    }

    @Test
    fun cuesSemVersaoContinuaSendoMapa() {
        val old = "{\"sectionId\":\"s\",\"mainIdea\":\"i\",\"anchorPhrase\":\"a\"," +
            "\"cues\":[{\"kind\":\"HOOK\",\"text\":\"Abertura\"}]}"
        val c = tribunaCapsuleFromJson(old)!!
        assertEquals(0, c.cueSheetVersion)
        assertEquals(1, c.cues.size)
        assertEquals(TribunaCueKind.HOOK, c.cues[0].kind)
    }
}
