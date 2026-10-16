package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.planning.Audience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #8f — extração de tema/tempo/público do pedido de Necessidades Locais. Puro. */
class LocalNeedsExtractionTest {

    @Test
    fun temaETempoDefault() {
        val i = ChatRouter.extractLocalNeedsInputs("cria um discurso curto sobre esperança")!!
        assertEquals("esperança", i.theme)
        assertEquals(5, i.minutes)
        assertEquals(Audience.GENERAL, i.audience)
    }

    @Test
    fun tempoExplicito() {
        val i = ChatRouter.extractLocalNeedsInputs("discurso de 10 minutos sobre perdão")!!
        assertEquals("perdão", i.theme)
        assertEquals(10, i.minutes)
    }

    @Test
    fun publicoNovos() {
        val i = ChatRouter.extractLocalNeedsInputs("discurso curto para os novos sobre fé")!!
        assertEquals(Audience.NEW, i.audience)
    }

    @Test
    fun semTemaEhNull() {
        assertNull(ChatRouter.extractLocalNeedsInputs("cria um discurso curto"))
    }
}
