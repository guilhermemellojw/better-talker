package com.bettertalker.app.data.planning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineJsonGuardTest {

    @Test
    fun detectaJsonDeOutlineMesmoComAliasesECamposQuebrados() {
        assertTrue(
            looksLikeOutlineJson(
                """{"title":"Ponto 1","summary":"Resumo","sections":[
                    {"title":"A","minutes":1.5,"mainldea":"x"}]}""",
            ),
        )
        assertTrue(
            looksLikeOutlineJson(
                """{"title":"Ponto 1","summary":"Resumo","sections":[
                    {"title":"A","minutes":2,"mainIdea":"x"}]}""",
            ),
        )
    }

    @Test
    fun naoDetectaConversaNemJsonQualquer() {
        assertFalse(looksLikeOutlineJson("A estrutura do Ponto 1 está boa."))
        assertFalse(looksLikeOutlineJson("""{"foo":1}"""))
        assertFalse(looksLikeOutlineJson(""))
    }
}
