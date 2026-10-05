package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3 — render do marcador de criação (tags ocultas + badge 💡). */
class SuggestionUiTest {

    @Test
    fun removeParDeTagsEFlagLigado() {
        val r = stripSuggestionTags("Veja: 〈sugestão〉uma metáfora do rio〈/sugestão〉 fim.")
        assertTrue(r.hasSuggestion)
        assertEquals("Veja: uma metáfora do rio fim.", r.text)
        assertFalse(r.text.contains("〈sugestão〉"))
        assertFalse(r.text.contains("〈/sugestão〉"))
    }

    @Test
    fun semTagNaoMuda() {
        val r = stripSuggestionTags("Texto normal sem marcador.")
        assertFalse(r.hasSuggestion)
        assertEquals("Texto normal sem marcador.", r.text)
    }

    @Test
    fun tagsSoltasTambemSomem() {
        val r = stripSuggestionTags("abre 〈sugestão〉 sem fechar")
        assertTrue(r.hasSuggestion)
        assertFalse(r.text.contains("〈sugestão〉"))
    }

    @Test
    fun multiplosParesRemovidos() {
        val r = stripSuggestionTags("〈sugestão〉A〈/sugestão〉 meio 〈sugestão〉B〈/sugestão〉")
        assertTrue(r.hasSuggestion)
        assertEquals("A meio B", r.text)
    }

    @Test
    fun vazioPermaneceVazio() {
        assertEquals("", stripSuggestionTags("").text)
    }
}
