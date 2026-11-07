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
    }
}
