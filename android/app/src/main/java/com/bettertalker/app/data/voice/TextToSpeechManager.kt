package com.bettertalker.app.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.bettertalker.app.data.llm.SUGGESTION_CLOSE
import com.bettertalker.app.data.llm.SUGGESTION_OPEN
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Idioma do TTS (pt-BR). */
const val TTS_LANGUAGE_TAG = "pt-BR"

/** Aviso quando o aparelho não tem a voz pt-BR instalada. */
const val TTS_LANGUAGE_UNAVAILABLE =
    "A voz em português não está disponível neste aparelho. Verifique as vozes do sistema."

private val SUGGESTION_PAIR = Regex(
    Regex.escape(SUGGESTION_OPEN) + "(.*?)" + Regex.escape(SUGGESTION_CLOSE),
    RegexOption.DOT_MATCHES_ALL,
)

/**
 * T2 (voz): prepara o texto PÓS-GATE para leitura em voz alta.
 *
 * - Remove as tags `〈sugestão〉…〈/sugestão〉` (o conteúdo fica).
 * - Verbaliza os avisos: "💡 Sugestão criativa:" → "Uma sugestão criativa:";
 *   "⚠️ Revise" → "Atenção: revise.".
 * - Remove markdown (`**`, `##`, listas, citações, links, código).
 * - Remove emojis não verbalizáveis (📖, ⚠️, ✔…).
 * - Não lê cards de proveniência (linhas "📖 …").
 * - Mantém a pontuação (o TTS nativo respeita as pausas). Puro/testável.
 */
internal fun prepareForSpeech(raw: String): String {
    if (raw.isBlank()) return ""
    var t = raw
    // 1. Tags de sugestão (par + soltas).
    t = SUGGESTION_PAIR.replace(t) { it.groupValues[1] }
    t = t.replace(SUGGESTION_OPEN, "").replace(SUGGESTION_CLOSE, "")
    // 2. Verbaliza os marcadores ANTES de remover os emojis.
    t = t.replace("💡 Sugestão criativa:", "Uma sugestão criativa:")
    t = t.replace("💡", "")
    t = t.replace(Regex("""⚠️\s*Revise:?"""), "Atenção: revise. ")
    t = t.replace("⚠️", "")
    // 3. Cards de proveniência não são conteúdo.
    t = t.lines().filterNot { it.trimStart().startsWith("📖") }.joinToString("\n")
    // 4. Markdown → fala.
    t = t.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1") // links
    t = t.replace(Regex("""`{1,3}([^`]*)`{1,3}"""), "$1") // código
    t = t.replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "") // títulos
    t = t.replace(Regex("""(?m)^\s{0,3}>\s?"""), "") // citações
    t = t.replace(Regex("""(?m)^\s{0,3}[-*•]\s+"""), "") // listas
    t = t.replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
    t = t.replace(Regex("""__([^_]+)__"""), "$1")
    t = t.replace(Regex("""(?<![\w*])\*([^*\n]+)\*(?![\w*])"""), "$1")
    t = t.replace(Regex("""(?<![\w_])_([^_\n]+)_(?![\w_])"""), "$1")
    // 5. Emojis restantes não verbalizáveis.
    for (e in NON_SPEECH_EMOJIS) t = t.replace(e, "")
    // 6. Espaços/pontuação limpos (mantém vírgulas e pontos — pausas do TTS).
    t = t.replace(Regex("""[ \t]{2,}"""), " ")
    t = t.replace(Regex("""\n{3,}"""), "\n\n")
    t = t.lines().joinToString("\n") { it.trimEnd() }.trim()
    return t
}

/** Emojis/glifos que o TTS não verbaliza (lidos como "caixa" ou ignorados). */
private val NON_SPEECH_EMOJIS = listOf(
    "✔", "✓", "✅", "❌", "🔗", "📌", "🎯", "📚", "🎧", "🎙", "🧠", "💬",
    "✍", "🙌", "😊", "✨", "⭐", "▶", "⏸", "ℹ", "\uFE0F", "\u200D",
)

/**
 * Wrapper do [TextToSpeech] nativo (pt-BR), sem biblioteca externa.
 *
 * - Inicialização assíncrona (`onInit`): [ready] liga quando a voz pt-BR está
 *   disponível; [languageAvailable] falso → a UI avisa o usuário.
 * - [speak] sempre recebe o texto PÓS-GATE ([prepareForSpeech]).
 * - Uma fala por vez: [speak] para a anterior antes de começar.
 * - Ciclo de vida: [shutdown] no `onCleared` do ViewModel.
 */
class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var languageOk = false

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _initialized = MutableStateFlow(false)

    /** A inicialização do motor terminou (independente de a voz pt-BR existir). */
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Falso quando o aparelho não tem dados/voz pt-BR. */
    val languageAvailable: Boolean get() = languageOk

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("pt", "BR"))
                languageOk = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                _ready.value = languageOk
                tts?.setOnUtteranceProgressListener(
                    object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _speaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _speaking.value = false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _speaking.value = false
                        }
                    }
                )
            } else {
                languageOk = false
                _ready.value = false
            }
            _initialized.value = true
        }
    }

    /**
     * Fala [text] (já pós-gate). Para a leitura anterior antes de começar —
     * nunca duas vozes ao mesmo tempo.
     */
    fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f) {
        val engine = tts ?: return
        if (!languageOk || text.isBlank()) return
        engine.stop()
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        _speaking.value = true
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bt-speech")
    }

    fun stop() {
        runCatching { tts?.stop() }
        _speaking.value = false
    }

    /** Chamado quando o TTS termina a fala (a UI também observa isSpeaking). */
    fun onDone() {
        _speaking.value = false
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        _ready.value = false
        _initialized.value = false
        _speaking.value = false
    }
}
