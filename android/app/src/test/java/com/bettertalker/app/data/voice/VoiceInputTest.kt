package com.bettertalker.app.data.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1 (voz): mensagens de erro do STT e modelo de estado. Puro/testável. */
class VoiceInputTest {

    @Test
    fun voiceErrorMessage_mapeiaCodigosConhecidos() {
        assertEquals(
            "Permita o microfone para usar a voz.",
            voiceErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        )
        assertEquals(
            "Não entendi — tente falar mais perto do microfone.",
            voiceErrorMessage(SpeechRecognizer.ERROR_NO_MATCH)
        )
        assertTrue(
            voiceErrorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT).contains("Não ouvi nada")
        )
    }

    @Test
    fun voiceErrorMessage_codigoDesconhecidoTemFallbackHumano() {
        val msg = voiceErrorMessage(99_999)
        assertTrue(msg.contains("Tente de novo"))
        assertFalse("não vaza o código", msg.contains("99"))
    }

    @Test
    fun voiceState_resultCarregaOTexto() {
        assertEquals("olá mundo", VoiceInputState.Result("olá mundo").text)
    }
}
