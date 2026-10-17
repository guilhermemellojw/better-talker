package com.bettertalker.app.data.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * #23-fix-a: interface comum de TTS. O [TextToSpeechManager] (nativo) e o
 * [PiperTtsManager] implementam; o [VoiceRouter] escolhe em runtime.
 * (Assinaturas espelham o TextToSpeechManager — zero mudança de lógica.)
 */
interface VoiceEngine {
    val speaking: StateFlow<Boolean>
    val ready: StateFlow<Boolean>
    val initialized: StateFlow<Boolean>
    fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f)
    fun stop()
    fun isSpeaking(): Boolean
    fun shutdown()
}
