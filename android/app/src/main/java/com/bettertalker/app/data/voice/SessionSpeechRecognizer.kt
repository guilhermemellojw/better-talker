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
    data object Paused : SessionSpeechState
    data object Processing : SessionSpeechState
    data class Interrupted(val message: String) : SessionSpeechState
}

/**
 * Acumulador puro de transcrições de sessão.
 *
 * O SpeechRecognizer nativo devolve um segmento por vez; o Ensaio reinicia a
 * escuta e concatena os resultados. Duplicatas exatas (alguns serviços
 * repetem o último segmento) são ignoradas. Pausar/retomar NÃO limpa o texto;
 * só [clear] (descartar/fechar) apaga. Puro/testável.
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
 * Controle explícito: iniciar → pausar → retomar → parar. O app nunca encerra
 * a sessão sozinho:
 * - silêncio (`NO_MATCH`/`SPEECH_TIMEOUT`) reinicia a escuta em segundo plano;
 * - falhas persistentes viram [SessionSpeechState.Interrupted] e preservam a
 *   transcrição para o usuário decidir entre retomar ou parar;
 * - `Result` só é calculado quando o chamador pede [stopSession].
 *
 * `EXTRA_PREFER_OFFLINE` é tentado primeiro; se o serviço offline falhar,
 * tenta o online. Nada é gravado em disco.
 */
class SessionSpeechRecognizer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val accumulator = SpeechTranscriptAccumulator()
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var paused = false
    private var stopRequested = false
    private var preferOffline = true
    private var onlineFallbackUsed = false
    private var retryCount = 0
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
            _state.value = SessionSpeechState.Interrupted(
                "Reconhecimento de voz indisponível neste aparelho."
            )
            return
        }
        active = true
        paused = false
        stopRequested = false
        preferOffline = true
        onlineFallbackUsed = false
        retryCount = 0
        _state.value = SessionSpeechState.Listening
        // Dá tempo ao serviço anterior de soltar o microfone (SODA/Google).
        main.postDelayed({ if (active && !paused && !stopRequested) createAndStart() }, START_DELAY_MS)
    }

    /** Pausa sem perder a transcrição; o serviço é liberado. */
    fun pauseSession() {
        if (!active && !paused) return
        paused = true
        active = false
        stopRequested = false
        removeRestart()
        finalizeRunnable?.let(main::removeCallbacks)
        finalizeRunnable = null
        destroyRecognizer()
        _partial.value = ""
        _state.value = SessionSpeechState.Paused
    }

    /** Retoma a escuta do ponto anterior, mantendo o texto acumulado. */
    fun resumeSession() {
        if (!paused && _state.value !is SessionSpeechState.Interrupted) return
        paused = false
        active = true
        stopRequested = false
        retryCount = 0
        _state.value = SessionSpeechState.Listening
        main.postDelayed({ if (active && !paused && !stopRequested) createAndStart() }, START_DELAY_MS)
    }

    /** Parar explícito: pede o último segmento e finaliza em Idle. */
    fun stopSession() {
        if (paused || _state.value is SessionSpeechState.Interrupted) {
            finalizeStopped()
            return
        }
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
        paused = false
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
        if (!active || paused || stopRequested) return
        destroyRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (active && !paused) _state.value = SessionSpeechState.Listening
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
                    retryCount = 0
                }
                _partial.value = ""
                if (stopRequested) {
                    finalizeStopped()
                } else if (active && !paused) {
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
            _state.value = SessionSpeechState.Interrupted(
                "Não foi possível abrir o microfone. Tente de novo."
            )
        }
    }

    private fun handleError(error: Int) {
        if (stopRequested) {
            finalizeStopped()
            return
        }
        if (!active || paused) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Silêncio é normal no ensaio: reinicia sem pausar nem perder
                // o texto já transcrito.
                _partial.value = ""
                scheduleRestart(RESTART_SILENCE_MS)
            }
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
                retryOrInterrupt(error, RESTART_RATE_LIMIT_MS, maxRetries = 3)
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER ->
                retryOrInterrupt(error, RESTART_NETWORK_MS, maxRetries = 3)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                retryOrInterrupt(error, RESTART_BUSY_MS, maxRetries = 5)
            SpeechRecognizer.ERROR_CLIENT ->
                retryOrInterrupt(error, RESTART_BUSY_MS, maxRetries = 3)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_AUDIO -> interrupt(error)
            else -> {
                android.util.Log.w(
                    "SessionSTT",
                    "SpeechRecognizer error=$error offline=$preferOffline",
                )
                // Alguns aparelhos têm o modelo offline quebrado (SODA).
                // Segunda tentativa usa o serviço online do sistema; se
                // também falhar, aí sim interrompe (sem gerar resultado).
                if (preferOffline && !onlineFallbackUsed) {
                    preferOffline = false
                    onlineFallbackUsed = true
                    scheduleRestart(RESTART_NETWORK_MS)
                } else {
                    interrupt(error)
                }
            }
        }
    }

    private fun retryOrInterrupt(error: Int, delayMs: Long, maxRetries: Int) {
        retryCount++
        if (retryCount <= maxRetries) {
            scheduleRestart(delayMs)
        } else {
            interrupt(error)
        }
    }

    private fun interrupt(error: Int) {
        android.util.Log.w(
            "SessionSTT",
            "interrupted error=$error retries=$retryCount offline=$preferOffline",
        )
        active = false
        paused = false
        removeRestart()
        finalizeRunnable?.let(main::removeCallbacks)
        finalizeRunnable = null
        destroyRecognizer()
        _partial.value = ""
        _state.value = SessionSpeechState.Interrupted(voiceErrorMessage(error))
    }

    private fun scheduleRestart(delayMs: Long = RESTART_MS) {
        if (!active || paused || stopRequested) return
        removeRestart()
        val runnable = Runnable { if (active && !paused && !stopRequested) createAndStart() }
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
        paused = false
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
        private const val RESTART_NETWORK_MS = 2_500L
        private const val RESTART_RATE_LIMIT_MS = 4_000L
        private const val FINALIZE_TIMEOUT_MS = 3_000L
    }
}
