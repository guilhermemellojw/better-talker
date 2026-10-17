package com.bettertalker.app.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * #23-fix-a: roteador de TTS — Piper quando ligado E pronto; senão nativo.
 *
 * `active` é lido a cada chamada (nunca cacheado): se o Piper falhar em
 * runtime, o próximo speak cai no nativo sozinho.
 */
class VoiceRouter(
    private val native: VoiceEngine,
    private val piper: VoiceEngine,
    private val modelDir: File,
) : VoiceEngine {

    /** true = usar Piper (se pronto); false = nativo. */
    private val _premiumEnabled = MutableStateFlow(false)
    val premiumEnabled: StateFlow<Boolean> = _premiumEnabled.asStateFlow()

    private val active: VoiceEngine
        get() = if (_premiumEnabled.value && piper.ready.value) piper else native

    override val speaking: StateFlow<Boolean> get() = active.speaking
    override val ready: StateFlow<Boolean> get() = active.ready
    override val initialized: StateFlow<Boolean> get() = active.initialized

    override fun speak(text: String, rate: Float, pitch: Float) = active.speak(text, rate, pitch)
    override fun stop() = active.stop()
    override fun isSpeaking(): Boolean = active.isSpeaking()

    override fun shutdown() {
        native.shutdown()
        piper.shutdown()
    }

    fun setPremiumEnabled(enabled: Boolean) {
        _premiumEnabled.value = enabled
        // #23-fix-a: ligar o premium já pré-carrega o Piper (o lazy sozinho
        // nunca ativava: ready só vira true depois do 1º speak).
        if (enabled) piper.warmUp()
    }

    /**
     * Se o premium está ligado mas o modelo sumiu do disco, desliga o toggle
     * (a UI reflete). O download é sempre explícito na Settings.
     */
    fun ensurePiperOrFallback() {
        if (_premiumEnabled.value && !piper.ready.value && !File(modelDir, "model.onnx").exists()) {
            _premiumEnabled.value = false
        }
    }
}

/** #23-fix-a: resultado do toggle "Voz premium" (puro/testável). */
sealed interface VoicePremiumToggle {
    data object Activated : VoicePremiumToggle
    data object Deactivated : VoicePremiumToggle
    data object NeedsDownload : VoicePremiumToggle
}

/**
 * Regra do toggle: ligar exige o modelo baixado; desligar é sempre livre.
 */
internal fun resolveVoicePremiumToggle(
    desired: Boolean,
    downloaded: Boolean,
): VoicePremiumToggle = when {
    !desired -> VoicePremiumToggle.Deactivated
    downloaded -> VoicePremiumToggle.Activated
    else -> VoicePremiumToggle.NeedsDownload
}
