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
 * Usa o **serviço padrão do sistema** com o intent mínimo — o mesmo caminho
 * dos apps de gravação/conversação que funcionam no aparelho. Não força
 * `EXTRA_PREFER_OFFLINE` nem extras de silêncio (o SODA offline de alguns
 * aparelhos cancelava a sessão com “Mic provider not setup”).
 *
 * - Uma instância de [SpeechRecognizer] é reaproveitada entre segmentos;
 *   criar/destruir a cada `onResults` causava corrida no serviço.
 * - Controle explícito: iniciar → pausar → retomar → parar.
 * - Silêncio reinicia; falha persistente vira [SessionSpeechState.Interrupted]
 *   e preserva a transcrição para Retomar/Parar.
 * - `Result` só é calculado quando o chamador pede [stopSession].
 * - Nada é gravado em disco.
 */
class SessionSpeechRecognizer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val accumulator = SpeechTranscriptAccumulator()
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var paused = false
    private var stopRequested = false
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

    /** true a partir da primeira fala detectada (não do toque em Analisar). */
    private val _speechStarted = MutableStateFlow(false)
    val speechStarted: StateFlow<Boolean> = _speechStarted.asStateFlow()

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
        retryCount = 0
        _speechStarted.value = false
        _state.value = SessionSpeechState.Listening
        // Dá tempo ao serviço anterior de soltar o microfone.
        main.postDelayed({ if (active && !paused && !stopRequested) startListening() }, START_DELAY_MS)
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
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
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
        main.postDelayed({ if (active && !paused && !stopRequested) startListening() }, START_DELAY_MS)
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
        _speechStarted.value = false
        _state.value = SessionSpeechState.Idle
    }

    /** Descarta a transcrição depois de calcular as métricas. */
    fun clearTranscript() {
        accumulator.clear()
        _transcript.value = ""
        _partial.value = ""
    }

    fun destroy() = cancelSession()

    private fun startListening() {
        if (!active || paused || stopRequested) return
        val r = recognizer ?: createRecognizer()
        runCatching { r.startListening(buildIntent()) }.onFailure {
            active = false
            destroyRecognizer()
            _state.value = SessionSpeechState.Interrupted(
                "Não foi possível abrir o microfone. Tente de novo."
            )
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (active && !paused) _state.value = SessionSpeechState.Listening
            }

            override fun onBeginningOfSpeech() {
                // Ruído de fundo também dispara; o relógio de fala começa no
                // primeiro texto parcial.
            }

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
                    _speechStarted.value = true
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
                if (text.any(Char::isLetterOrDigit)) {
                    _speechStarted.value = true
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        return r
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun handleError(error: Int) {
        if (stopRequested) {
            finalizeStopped()
            return
        }
        if (!active || paused) return
        android.util.Log.w("SessionSTT", "SpeechRecognizer error=$error retries=$retryCount")
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Silêncio é normal no ensaio: reinicia sem pausar nem perder
                // o texto já transcrito.
                _partial.value = ""
                scheduleRestart(RESTART_SILENCE_MS)
            }
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                // Insistir aqui só aumenta o bloqueio do serviço. Interrompe
                // com mensagem clara; o usuário decide quando retomar.
                interrupt(error)
            }
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER ->
                retryOrInterrupt(error, RESTART_NETWORK_MS, maxRetries = 1)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                retryOrInterrupt(error, RESTART_BUSY_MS, maxRetries = 2)
            SpeechRecognizer.ERROR_CLIENT ->
                retryOrInterrupt(error, RESTART_BUSY_MS, maxRetries = 1)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_AUDIO -> interrupt(error)
            else -> interrupt(error)
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
        android.util.Log.w("SessionSTT", "interrupted error=$error retries=$retryCount")
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
        // Libera o segmento anterior antes de reutilizar a instância.
        runCatching { recognizer?.cancel() }
        val runnable = Runnable { if (active && !paused && !stopRequested) startListening() }
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
        private const val START_DELAY_MS = 300L
        private const val RESTART_MS = 300L
        private const val RESTART_SILENCE_MS = 2_000L
        private const val RESTART_BUSY_MS = 2_000L
        private const val RESTART_NETWORK_MS = 5_000L
        private const val FINALIZE_TIMEOUT_MS = 3_000L
    }
}
