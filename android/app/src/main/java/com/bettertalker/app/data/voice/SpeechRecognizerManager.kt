package com.bettertalker.app.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado da entrada por voz (STT nativo). */
sealed interface VoiceInputState {
    data object Idle : VoiceInputState

    /** Microfone aberto, aguardando a fala. */
    data object Listening : VoiceInputState

    /** Fala encerrada; aguardando o resultado do serviço. */
    data object Processing : VoiceInputState

    /** Texto transcrito — pronto para o composer (editável). */
    data class Result(val text: String) : VoiceInputState

    /** Falha com mensagem humana (nunca stack/HTTP/código). */
    data class Error(val message: String) : VoiceInputState
}

/**
 * Mensagem humana para o código de erro do reconhecedor. Puro/testável.
 */
internal fun voiceErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "Falha ao capturar o áudio. Tente de novo."
    SpeechRecognizer.ERROR_CLIENT -> "O reconhecimento foi interrompido. Tente de novo."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permita o microfone para usar a voz."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        "Sem conexão para o reconhecimento de voz. Tente de novo."
    SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi — tente falar mais perto do microfone."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecedor está ocupado. Tente de novo."
    SpeechRecognizer.ERROR_SERVER -> "O serviço de voz falhou. Tente de novo."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada — toque no microfone e fale."
    else -> "Não consegui ouvir. Tente de novo."
}

/**
 * Wrapper do [SpeechRecognizer] nativo (pt-BR), sem biblioteca externa.
 *
 * - **O áudio nunca é persistido**: nada é gravado em disco; o serviço do
 *   aparelho devolve apenas o texto transcrito.
 * - `EXTRA_PARTIAL_RESULTS = false` (MVP sem streaming parcial).
 * - Timeout configurável (default [DEFAULT_TIMEOUT_MS]) — encerra a escuta e
 *   mostra erro amigável.
 * - Ciclo de vida: [destroy] no `onCleared` do ViewModel.
 */
class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    private val timeoutRunnable = Runnable {
        if (_state.value is VoiceInputState.Listening || _state.value is VoiceInputState.Processing) {
            stopInternal()
            _state.value = VoiceInputState.Error("Tempo esgotado — toque no microfone e tente de novo.")
        }
    }

    /** Inicia a escuta em pt-BR (reinicia se já houver uma sessão). */
    fun start(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        stopInternal()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceInputState.Error(
                "Reconhecimento de voz indisponível neste aparelho."
            )
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = VoiceInputState.Listening
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _state.value = VoiceInputState.Processing
            }

            override fun onError(error: Int) {
                handler.removeCallbacks(timeoutRunnable)
                stopInternal()
                _state.value = VoiceInputState.Error(voiceErrorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                handler.removeCallbacks(timeoutRunnable)
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                stopInternal()
                _state.value = if (text.isBlank()) {
                    VoiceInputState.Error("Não entendi — tente de novo.")
                } else {
                    VoiceInputState.Result(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _state.value = VoiceInputState.Listening
        handler.removeCallbacks(timeoutRunnable)
        if (timeoutMs > 0) handler.postDelayed(timeoutRunnable, timeoutMs)
        runCatching { r.startListening(intent) }
    }

    /** Cancela sem transcrever (botão X). */
    fun cancel() {
        handler.removeCallbacks(timeoutRunnable)
        stopInternal()
        _state.value = VoiceInputState.Idle
    }

    /** Para a escuta (lifecycle) sem deixar erro visível. */
    fun stop() {
        handler.removeCallbacks(timeoutRunnable)
        stopInternal()
        if (_state.value is VoiceInputState.Listening || _state.value is VoiceInputState.Processing) {
            _state.value = VoiceInputState.Idle
        }
    }

    /** Limpa Result/Error após o consumo no composer. */
    fun consume() {
        if (_state.value is VoiceInputState.Result || _state.value is VoiceInputState.Error) {
            _state.value = VoiceInputState.Idle
        }
    }

    /** Encerra o reconhecedor (onCleared do ViewModel). */
    fun destroy() {
        handler.removeCallbacks(timeoutRunnable)
        stopInternal()
        _state.value = VoiceInputState.Idle
    }

    private fun stopInternal() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    companion object {
        /** Idioma do reconhecimento (pt-BR). */
        const val LANGUAGE_TAG = "pt-BR"

        /** Teto de escuta (o serviço do aparelho também impõe limites próprios). */
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
