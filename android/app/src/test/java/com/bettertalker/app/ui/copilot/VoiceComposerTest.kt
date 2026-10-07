package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.voice.VoiceInputState
import org.junit.Assert.assertEquals
import org.junit.Test

/** T3 (voz): rótulos do botão de microfone no composer. Puro/testável. */
class VoiceComposerTest {

    @Test
    fun voiceElapsedLabel_formataMinutoESegundo() {
        assertEquals("Ouvindo… 0:00", voiceElapsedLabel(0))
        assertEquals("Ouvindo… 0:07", voiceElapsedLabel(7))
        assertEquals("Ouvindo… 1:03", voiceElapsedLabel(63))
        assertEquals("Ouvindo… 0:00", voiceElapsedLabel(-2))
    }

    @Test
    fun voiceMicDescription_porEstado() {
        assertEquals("Falar", voiceMicDescription(VoiceInputState.Idle))
        assertEquals("Ouvindo — conclua ou cancele", voiceMicDescription(VoiceInputState.Listening))
        assertEquals("Transcrevendo", voiceMicDescription(VoiceInputState.Processing))
        assertEquals("Falar", voiceMicDescription(VoiceInputState.Error("x")))
    }
}
