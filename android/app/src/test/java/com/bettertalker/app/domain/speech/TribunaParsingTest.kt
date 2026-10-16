package com.bettertalker.app.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #20-fix-a — parse da resposta JSON da Tribuna. Puro. */
class TribunaParsingTest {

    @Test
    fun respostaComTresSecoes() {
        val json = """
            [
              {"sectionId":"sec-1","mainIdea":"A","bibleRef":"Sal 103:20","anchorPhrase":"B","transition":"C"},
              {"sectionId":"sec-2","mainIdea":"D","bibleRef":null,"anchorPhrase":"E","transition":"F"},
              {"sectionId":"sec-3","mainIdea":"G","bibleRef":"Jó 38:31","anchorPhrase":"H","transition":null}
            ]
        """.trimIndent()
        val caps = tribunaCapsulesFromJson(json)
        assertEquals(3, caps.size)
        assertEquals("sec-1", caps[0].sectionId)
        assertEquals("Sal 103:20", caps[0].bibleRef)
        assertEquals("sec-3", caps[2].sectionId)
    }

    @Test
    fun bibleRefNullViraNull() {
        val caps = tribunaCapsulesFromJson(
            """[{"sectionId":"s","mainIdea":"i","bibleRef":null,"anchorPhrase":"a"}]"""
        )
        assertEquals(1, caps.size)
        assertNull(caps[0].bibleRef)
    }

    @Test
    fun itemSemCamposObrigatoriosEhIgnorado() {
        val caps = tribunaCapsulesFromJson(
            """[{"sectionId":"s1"},{"sectionId":"s2","mainIdea":"i","anchorPhrase":"a"}]"""
        )
        assertEquals(1, caps.size)
        assertEquals("s2", caps[0].sectionId)
    }
}
