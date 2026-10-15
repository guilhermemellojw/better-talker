package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #15b — render do chat: badge de sugestão preservado (as tags do par
 * `〈sugestão〉` saem do texto) e as demais tags internas (`〈decisão〉`,
 * órfãs) nunca renderizam. Puro, sem Compose.
 */
class ChatRenderTagsTest {

    @Test
    fun sugestao_geraBadge_eTextoSemTags() {
        val r = cleanedForRender("Veja: 〈sugestão〉texto criativo〈/sugestão〉")
        assertTrue(r.hasSuggestion)
        assertEquals("Veja: texto criativo", r.text)
    }

    @Test
    fun sugestaoOrfa_eRemovida() {
        val r = cleanedForRender("Texto ok〈/sugestão〉")
        assertFalse(r.text.contains("〈"))
        assertEquals("Texto ok", r.text)
    }

    @Test
    fun decisao_naoRenderizaTags() {
        val r = cleanedForRender("〈decisão〉usar a ilustração do farol〈/decisão〉 Fim.")
        assertFalse(r.text.contains("〈"))
        assertTrue(r.text.contains("usar a ilustração do farol"))
        assertEquals("usar a ilustração do farol Fim.", r.text)
    }

    @Test
    fun semTags_textoInalterado() {
        val r = cleanedForRender("Resposta normal, sem marcadores.")
        assertEquals("Resposta normal, sem marcadores.", r.text)
        assertFalse(r.hasSuggestion)
    }

    @Test
    fun sugestaoMaisDecisao_ambasTratadas() {
        val r = cleanedForRender("〈sugestão〉criação〈/sugestão〉 〈decisão〉fechar assim〈/decisão〉")
        assertTrue(r.hasSuggestion)
        assertEquals("criação fechar assim", r.text)
    }
}
