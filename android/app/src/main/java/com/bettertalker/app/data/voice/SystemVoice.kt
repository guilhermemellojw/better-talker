package com.bettertalker.app.data.voice

/** Voz do motor TTS do sistema, já normalizada para a UI. */
data class SystemVoice(
    /** Identificador estável do Android (`Voice.name`). */
    val name: String,
    val localeTag: String,
    val label: String,
    val requiresNetwork: Boolean,
    val quality: Int,
)

/** Interface opcional para engines que expõem as vozes do sistema. */
interface SystemVoiceEngine {
    fun systemVoices(): List<SystemVoice>
    fun setSystemVoice(name: String?)
}

/**
 * Filtra e ordena as vozes para o seletor:
 * - só português (pt/ pt-BR/ pt-PT…);
 * - offline antes de rede;
 * - maior qualidade primeiro;
 * - nome como desempate (determinístico).
 *
 * Puro/testável.
 */
fun systemVoiceOptions(voices: List<SystemVoice>): List<SystemVoice> =
    voices
        .filter { it.localeTag.lowercase().startsWith("pt") }
        .distinctBy { it.name }
        .sortedWith(
            compareBy<SystemVoice>(
                { it.requiresNetwork },
                { -it.quality },
                { it.name },
            ),
        )

/** Rótulo humano curto para a voz (locale + qualidade + offline/rede). */
fun systemVoiceLabel(
    localeDisplayName: String,
    quality: Int,
    requiresNetwork: Boolean,
): String {
    val qualityLabel = when (quality) {
        android.speech.tts.Voice.QUALITY_VERY_HIGH -> "qualidade muito alta"
        android.speech.tts.Voice.QUALITY_HIGH -> "qualidade alta"
        android.speech.tts.Voice.QUALITY_NORMAL -> "qualidade normal"
        android.speech.tts.Voice.QUALITY_LOW -> "qualidade baixa"
        else -> "qualidade padrão"
    }
    return "$localeDisplayName · $qualityLabel · " +
        if (requiresNetwork) "rede" else "offline"
}

/** true quando o aparelho/API permite o reconhecedor on-device. Puro/testável. */
fun useOnDeviceRecognizer(
    preferOnDevice: Boolean,
    onDeviceAvailable: Boolean,
    sdkInt: Int,
): Boolean = preferOnDevice && onDeviceAvailable && sdkInt >= 31

/** Aviso de fallback exibido só quando o usuário pediu on-device. */
const val STT_ON_DEVICE_FALLBACK =
    "Reconhecimento no aparelho indisponível; usando o serviço do sistema."
