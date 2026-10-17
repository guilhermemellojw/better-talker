package com.bettertalker.app.data.voice

import org.junit.Assert.assertTrue
import org.junit.Test

/** #23-fix-a: contrato — todos os engines implementam VoiceEngine. */
class VoiceEngineContractTest {

    @Test
    fun textToSpeechManagerImplementaVoiceEngine() {
        assertTrue(VoiceEngine::class.java.isAssignableFrom(TextToSpeechManager::class.java))
    }

    @Test
    fun piperTtsManagerImplementaVoiceEngine() {
        assertTrue(VoiceEngine::class.java.isAssignableFrom(PiperTtsManager::class.java))
    }

    @Test
    fun voiceRouterImplementaVoiceEngine() {
        assertTrue(VoiceEngine::class.java.isAssignableFrom(VoiceRouter::class.java))
    }
}
