package com.bettertalker.app.ui.aimodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** T4 — mapeamento puro do status do card DeepSeek. */
class DeepSeekStatusTest {

    @Test
    fun semChaveIgnoraSonda() {
        assertEquals(DeepSeekUiStatus.NO_KEY, deepSeekUiStatus("", null))
        assertEquals(DeepSeekUiStatus.NO_KEY, deepSeekUiStatus("", "online"))
    }

    @Test
    fun chaveSalvaSemSondaFicaNaoVerificada() {
        assertEquals(DeepSeekUiStatus.CONFIGURED, deepSeekUiStatus("sk-teste", null))
    }

    @Test
    fun falhaAoSalvarMostraErroEspecifico() {
        assertEquals(DeepSeekUiStatus.SAVE_ERROR, deepSeekUiStatus("", "save-error"))
        assertEquals(DeepSeekUiStatus.SAVE_ERROR, deepSeekUiStatus("sk", "save-error"))
    }

    @Test
    fun estadosDaSonda() {
        assertEquals(DeepSeekUiStatus.CHECKING, deepSeekUiStatus("sk", "checking"))
        assertEquals(DeepSeekUiStatus.ONLINE, deepSeekUiStatus("sk", "online"))
        assertEquals(DeepSeekUiStatus.AUTH_ERROR, deepSeekUiStatus("sk", "auth"))
        assertEquals(DeepSeekUiStatus.ERROR, deepSeekUiStatus("sk", "error"))
        assertEquals(DeepSeekUiStatus.ERROR, deepSeekUiStatus("sk", "qualquer-outro"))
    }
}
