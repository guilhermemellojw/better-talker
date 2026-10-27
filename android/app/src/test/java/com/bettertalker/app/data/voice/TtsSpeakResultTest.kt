package com.bettertalker.app.data.voice

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 voz: o `speak` só marca "falando" quando o engine aceitou. Puro. */
class TtsSpeakResultTest {

    @Test
    fun successMarcaFalando() {
        assertTrue(ttsSpeakSucceeded(TextToSpeech.SUCCESS))
    }

    @Test
    fun errorNaoDeixaOBotaoPreso() {
        assertFalse(ttsSpeakSucceeded(TextToSpeech.ERROR))
    }
}
