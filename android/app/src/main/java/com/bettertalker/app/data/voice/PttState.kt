package com.bettertalker.app.data.voice

/**
 * Fases visíveis do push-to-talk.
 *
 * O ciclo normal é:
 * `Idle → Starting → Listening → Transcribing → Thinking → (Speaking) → Idle`.
 * A máquina é pura e não conhece Android/Room; quem abre o microfone é o
 * [PttController].
 */
sealed interface PttState {
    data object Idle : PttState

    /** Botão pressionado; modelo/microfone abrindo. */
    data object Starting : PttState

    /** Microfone aberto, capturando enquanto o botão está pressionado. */
    data object Listening : PttState

    /** Botão solto; esperando o último segmento/final do STT. */
    data object Transcribing : PttState

    /** Transcrição enviada; aguardando a resposta do Copilot. */
    data object Thinking : PttState

    /** Resposta sendo lida em voz alta. */
    data object Speaking : PttState

    data class Error(val message: String) : PttState
}

/**
 * Transições do PTT. Puro/testável.
 *
 * Regras de segurança:
 * - só entra em [PttState.Starting] por [press];
 * - [release] só sai de [PttState.Listening] (nunca de Idle);
 * - [cancel] sempre volta para Idle e a UI deve parar o motor;
 * - pressionar durante [PttState.Speaking]/[PttState.Thinking] é barge-in
 *   explícito (a UI interrompe TTS/geração antes de abrir o microfone).
 */
class PttMachine {
    var state: PttState = PttState.Idle
        private set

    /** true = o controlador deve iniciar a escuta; false = toque ignorado. */
    fun press(): Boolean {
        if (state == PttState.Starting ||
            state == PttState.Listening ||
            state == PttState.Transcribing
        ) {
            return false
        }
        state = PttState.Starting
        return true
    }

    fun engineReady() {
        if (state == PttState.Starting) state = PttState.Listening
    }

    fun release() {
        if (state == PttState.Listening) state = PttState.Transcribing
    }

    fun transcriptReady() {
        if (state == PttState.Transcribing) state = PttState.Thinking
    }

    fun responseFinished(spoken: Boolean) {
        if (state == PttState.Thinking) {
            state = if (spoken) PttState.Speaking else PttState.Idle
        }
    }

    fun ttsStarted() {
        if (state == PttState.Thinking || state == PttState.Idle) {
            state = PttState.Speaking
        }
    }

    fun ttsFinished() {
        if (state == PttState.Speaking) state = PttState.Idle
    }

    fun cancel() {
        state = PttState.Idle
    }

    fun fail(message: String) {
        state = PttState.Error(message)
    }

    fun reset() {
        state = PttState.Idle
    }
}

/** Motor escolhido para o PTT. */
enum class PttSttChoice { LOCAL, ON_DEVICE, SYSTEM }

/**
 * Cancelamento por arrasto do PTT: para cima ou para a esquerda, além do
 * limiar (distância euclidiana). Arrastar só para baixo/direita nunca cancela.
 * Puro/testável; a UI converte dp → px.
 */
fun isPttCancelDrag(dx: Float, dy: Float, threshold: Float): Boolean {
    if (dx >= 0f && dy >= 0f) return false
    return dx * dx + dy * dy >= threshold * threshold
}

/**
 * Escolha do motor: local (sherpa) quando preferido e disponível; senão
 * on-device (API 31+); senão o serviço do sistema; sem nenhum → null.
 * Puro/testável.
 */
fun choosePttSttChoice(
    preferLocal: Boolean,
    localAvailable: Boolean,
    systemAvailable: Boolean,
    onDeviceAvailable: Boolean,
    sdkInt: Int,
): PttSttChoice? = when {
    preferLocal && localAvailable -> PttSttChoice.LOCAL
    !systemAvailable -> null
    onDeviceAvailable && sdkInt >= 31 -> PttSttChoice.ON_DEVICE
    else -> PttSttChoice.SYSTEM
}

/** Engine escolhido + rótulo honesto para a UI. */
data class PttSelection(
    val engine: SttEngine,
    val choice: PttSttChoice,
)

/** Cria/reaproveita o engine local ou o fallback de sistema. */
interface PttEngineProvider {
    fun create(): PttSelection?
    fun release() {}
}
