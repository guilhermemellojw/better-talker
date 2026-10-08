package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 (Mini Discurso P2): pedido de geração do mini discurso vindo do chat. */
class MiniSpeechRequestTest {

    @Test
    fun draftRequestFor_comEscopo_emitePedido() {
        val req = draftRequestFor("sec-1", nonce = 7L)
        assertEquals("sec-1", req?.sectionId)
        assertEquals(7L, req?.nonce)
    }

    @Test
    fun draftRequestFor_semEscopo_naoEmite() {
        assertNull(draftRequestFor(null))
        assertNull(draftRequestFor(""))
        assertNull(draftRequestFor("   "))
    }

    @Test
    fun mensagemSemEscopo_orientaAbrirTopico() {
        assertTrue(MINI_SPEECH_NO_SCOPE_MESSAGE.contains("tópico"))
        assertTrue(MINI_SPEECH_NO_SCOPE_MESSAGE.contains("editor"))
    }
}
