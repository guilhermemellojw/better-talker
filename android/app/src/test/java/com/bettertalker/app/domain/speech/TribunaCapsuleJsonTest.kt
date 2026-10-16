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
    }
}
