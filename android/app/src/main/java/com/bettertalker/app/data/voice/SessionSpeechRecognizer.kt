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

/** Estado do STT de sessão do Ensaio. */
sealed interface SessionSpeechState {
    data object Idle : SessionSpeechState
    data object Listening : SessionSpeechState
    data object Processing : SessionSpeechState
    data class Error(val message: String) : SessionSpeechState
}

/**
 * Acumulador puro de transcrições de sessão.
 *
 * O SpeechRecognizer nativo devolve um segmento por vez; o Ensaio reinicia a
 * escuta e concatena os resultados. Duplicatas exatas (alguns serviços
 * repetem o último segmento) são ignoradas. Puro/testável.
 */
class SpeechTranscriptAccumulator {
    private val builder = StringBuilder()
    private var lastFinal: String = ""

    fun addFinal(text: String): String {
        val clean = text.trim()
        if (clean.isBlank() || clean.none(Char::isLetterOrDigit) || clean == lastFinal) {
            return transcript()
        }
        if (builder.isNotEmpty()) builder.append(' ')
        builder.append(clean)
        lastFinal = clean
        return transcript()
    }

    fun transcript(): String = builder.toString().trim()

    fun clear() {
        builder.clear()
        lastFinal = ""
    }
}

/**
 * STT contínuo por segmentos para o Ensaio (T3-A).
 *
 * - O serviço nativo não é contínuo: cada `onResults`/erro recuperável
 *   reinicia a escuta automaticamente até [stopSession]/[cancelSession].
 * - `EXTRA_PREFER_OFFLINE`: pede o modo offline quando o aparelho suporta.
 *   O serviço ainda pode usar rede; a UI informa isso.
 * - Nada é gravado em disco: só o texto acumulado em memória.
 * - `stopSession` pede o último resultado e finaliza em [state] = Idle
 *   (ou Error); o chamador lê [transcript].
 */
class SessionSpeechRecognizer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val accumulator = SpeechTranscriptAccumulator()
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var stopRequested = false
    private var preferOffline = true
    private var onlineFallbackUsed = false
    private var restartRunnable: Runnable? = null
    private var finalizeRunnable: Runnable? = null

    private val _state = MutableStateFlow<SessionSpeechState>(SessionSpeechState.Idle)
    val state: StateFlow<SessionSpeechState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    /** Parcial do segmento atual (só para indicador; não entra no texto final). */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    fun startSession() {
        cancelSession()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = SessionSpeechState.Error(
                "Reconhecimento de voz indisponível neste aparelho."
            )
            return
        }
        active = true
        stopRequested = false
        preferOffline = true
        onlineFallbackUsed = false
        _state.value = SessionSpeechState.Listening
        // Dá tempo ao serviço anterior de soltar o microfone (SODA/Google).
        main.postDelayed({ if (active && !stopRequested) createAndStart() }, START_DELAY_MS)
    }

    /** Pede o último segmento e finaliza (onResults ou timeout de 3 s). */
    fun stopSession() {
        if (!active) {
            _state.value = SessionSpeechState.Idle
            return
        }
        stopRequested = true
        _state.value = SessionSpeechState.Processing
        removeRestart()
        runCatching { recognizer?.stopListening() }
        finalizeRunnable?.let(main::removeCallbacks)
        val runnable = Runnable { finalizeStopped() }
        finalizeRunnable = runnable
        main.postDelayed(runnable, FINALIZE_TIMEOUT_MS)
    }

    /** Cancela sem aproveitar o texto (fechar/descartar resultado). */
    fun cancelSession() {
        removeRestart()
        finalizeRunnable?.let(main::removeCallbacks)
        finalizeRunnable = null
        active = false
        stopRequested = false
        destroyRecognizer()
        accumulator.clear()
        _transcript.value = ""
        _partial.value = ""
        _state.value = SessionSpeechState.Idle
    }

    /** Descarta a transcrição depois de calcular as métricas. */
    fun clearTranscript() {
        accumulator.clear()
        _transcript.value = ""
        _partial.value = ""
    }

    fun destroy() = cancelSession()

    private fun createAndStart() {
        if (!active) return
        destroyRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (active) _state.value = SessionSpeechState.Listening
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (stopRequested) _state.value = SessionSpeechState.Processing
            }

            override fun onError(error: Int) {
                handleError(error)
            }

            override fun onResults(results: Bundle?) {
                finalizeRunnable?.let(main::removeCallbacks)
                finalizeRunnable = null
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    accumulator.addFinal(text)
                    _transcript.value = accumulator.transcript()
                }
                _partial.value = ""
                if (stopRequested) {
                    finalizeStopped()
                } else if (active) {
                    scheduleRestart()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                _partial.value = text.trim()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            // Segmentos menores: o Ensaio não pode esperar 10 s por resultado.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1500L,
            )
        }
        runCatching { r.startListening(intent) }.onFailure {
            active = false
            destroyRecognizer()
            _state.value = SessionSpeechState.Error(
                "Não foi possível abrir o microfone. Tente de novo."
            )
        }
    }

    private fun handleError(error: Int) {
        if (stopRequested) {
            finalizeStopped()
            return
        }
        if (!active) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Silêncio é normal no ensaio: reinicia sem expor erro.
                _partial.value = ""
                scheduleRestart(RESTART_SILENCE_MS)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(RESTART_BUSY_MS)
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                // O serviço nativo limita reinícios em sequência; espera mais
                // para não transformar silêncio em falha definitiva.
                scheduleRestart(RESTART_RATE_LIMIT_MS)
            }
            SpeechRecognizer.ERROR_CLIENT -> {
                // Pode ser o stopListening pedido pelo próprio app.
                if (!stopRequested) scheduleRestart(RESTART_BUSY_MS)
            }
            else -> {
                android.util.Log.w(
                    "SessionSTT",
                    "SpeechRecognizer error=$error offline=$preferOffline",
                )
                // Alguns aparelhos têm o modelo offline quebrado (SODA).
                // Segunda tentativa usa o serviço online do sistema; se
                // também falhar, aí sim encerra com feedback parcial.
                if (preferOffline && !onlineFallbackUsed &&
                    error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                    error != SpeechRecognizer.ERROR_AUDIO
                ) {
                    preferOffline = false
                    onlineFallbackUsed = true
                    scheduleRestart(RESTART_BUSY_MS)
                } else {
                    active = false
                    destroyRecognizer()
                    _state.value = SessionSpeechState.Error(voiceErrorMessage(error))
                }
            }
        }
    }

    private fun scheduleRestart(delayMs: Long = RESTART_MS) {
        if (!active || stopRequested) return
        removeRestart()
        val runnable = Runnable { if (active && !stopRequested) createAndStart() }
        restartRunnable = runnable
        main.postDelayed(runnable, delayMs)
    }

    private fun removeRestart() {
        restartRunnable?.let(main::removeCallbacks)
        restartRunnable = null
    }

    private fun finalizeStopped() {
        finalizeRunnable?.let(main::removeCallbacks)
        finalizeRunnable = null
        removeRestart()
        stopRequested = false
        active = false
        destroyRecognizer()
        _partial.value = ""
        _state.value = SessionSpeechState.Idle
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    companion object {
        const val LANGUAGE_TAG = "pt-BR"
        private const val START_DELAY_MS = 250L
        private const val RESTART_MS = 300L
        private const val RESTART_SILENCE_MS = 1_200L
        private const val RESTART_BUSY_MS = 1_500L
        private const val RESTART_RATE_LIMIT_MS = 4_000L
        private const val FINALIZE_TIMEOUT_MS = 3_000L
    }
}
