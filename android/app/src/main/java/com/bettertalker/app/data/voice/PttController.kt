package com.bettertalker.app.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controlador do push-to-talk: liga a [PttMachine] aos motores reais.
 *
 * - [press] abre o microfone (local ou fallback); [release] finaliza e
 *   entrega a transcrição; [cancel] descarta e libera o microfone.
 * - Nunca mantém escuta sem o botão pressionado.
 * - O motor é criado uma vez e reaproveitado; [dispose] libera o modelo.
 * - Callbacks são mutáveis porque a tela dona (nota/foco) pode mudar sem
 *   recriar o motor.
 */
class PttController(
    private val engines: PttEngineProvider,
) {
    private val machine = PttMachine()
    private val _state = MutableStateFlow<PttState>(PttState.Idle)
    val state: StateFlow<PttState> = _state.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _choice = MutableStateFlow<PttSttChoice?>(null)
    val choice: StateFlow<PttSttChoice?> = _choice.asStateFlow()

    /** Texto final pronto para enviar ao fio da nota. */
    var onTranscript: (String) -> Unit = {}

    /** Erro humano para snackbar; nunca stack/HTTP. */
    var onError: (String) -> Unit = {}

    /** A UI deve parar o TTS antes de abrir o microfone (barge-in). */
    var onStopSpeaking: () -> Unit = {}

    /** A UI deve cancelar a geração em andamento antes de abrir o microfone. */
    var onStopThinking: () -> Unit = {}

    private var selection: PttSelection? = null
    private var engine: SttEngine? = null
    private var voiceTurn = false
    private var disposed = false
    private val transcript = StringBuilder()

    fun press() {
        if (disposed) return
        val previous = machine.state
        if (!machine.press()) return
        sync()
        if (previous == PttState.Speaking) {
            runCatching { onStopSpeaking() }
        }
        if (previous == PttState.Thinking) {
            runCatching { onStopThinking() }
        }
        transcript.clear()
        _partial.value = ""
        val sel = selection ?: engines.create()?.also {
            selection = it
            _choice.value = it.choice
        }
        val eng = sel?.engine
        if (eng == null) {
            fail("Reconhecimento de voz indisponível neste aparelho.")
            return
        }
        engine = eng
        eng.start(
            onReady = {
                if (!disposed && machine.state == PttState.Starting) {
                    machine.engineReady()
                    sync()
                }
            },
            onPartial = { text ->
                if (!disposed && machine.state == PttState.Listening) {
                    _partial.value = text
                }
            },
            onFinal = { text ->
                if (!disposed) appendTranscript(text)
            },
            onError = { message ->
                if (!disposed && machine.state != PttState.Idle) fail(message)
            },
        )
    }

    fun release() {
        if (disposed) return
        when (machine.state) {
            // Soltou antes de o microfone abrir: não há fala para aproveitar.
            PttState.Starting -> cancel()
            PttState.Listening -> {
                machine.release()
                sync()
                val eng = engine
                if (eng == null) {
                    fail("Reconhecimento de voz indisponível neste aparelho.")
                    return
                }
                eng.stop {
                    if (disposed || machine.state != PttState.Transcribing) return@stop
                    val text = transcript.toString().trim()
                    if (text.isBlank()) {
                        fail("Não entendi — tente falar mais perto do microfone.")
                    } else {
                        voiceTurn = true
                        _partial.value = ""
                        runCatching { onTranscript(text) }
                        machine.transcriptReady()
                        sync()
                    }
                }
            }
            else -> Unit
        }
    }

    fun cancel() {
        if (disposed) return
        val wasSpeaking = machine.state == PttState.Speaking
        machine.cancel()
        sync()
        engine?.cancel()
        transcript.clear()
        _partial.value = ""
        voiceTurn = false
        if (wasSpeaking) runCatching { onStopSpeaking() }
    }

    /** Chamado pela UI quando o turno do Copilot terminou. */
    fun onResponseFinished(spoken: Boolean) {
        if (!voiceTurn || machine.state != PttState.Thinking) return
        machine.responseFinished(spoken)
        sync()
        if (!spoken) voiceTurn = false
    }

    fun onSpeakingStarted() {
        if (!voiceTurn) return
        machine.ttsStarted()
        sync()
    }

    fun onSpeakingFinished() {
        if (!voiceTurn) return
        machine.ttsFinished()
        sync()
        if (machine.state == PttState.Idle) voiceTurn = false
    }

    /** Limpa erro/turno sem abrir microfone. */
    fun reset() {
        if (disposed) return
        machine.reset()
        sync()
        voiceTurn = false
        _partial.value = ""
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val wasSpeaking = machine.state == PttState.Speaking
        machine.cancel()
        sync()
        engine?.cancel()
        engine = null
        selection = null
        _choice.value = null
        transcript.clear()
        _partial.value = ""
        voiceTurn = false
        runCatching { engines.release() }
        if (wasSpeaking) runCatching { onStopSpeaking() }
    }

    private fun appendTranscript(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (transcript.isNotEmpty()) transcript.append(' ')
        transcript.append(clean)
    }

    private fun fail(message: String) {
        // Segurança: erro no meio da escuta não pode deixar o microfone aberto.
        engine?.cancel()
        machine.fail(message)
        sync()
        runCatching { onError(message) }
    }

    private fun sync() {
        _state.value = machine.state
    }
}
