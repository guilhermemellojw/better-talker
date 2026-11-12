package com.bettertalker.app.data.voice

import android.content.Context
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Adapta o [SpeechRecognizerManager] (STT do sistema/on-device) ao
 * [SttEngine] para o PTT.
 *
 * O PTT só precisa de start/stop/cancel; pausa/retomada existem para a
 * interface comum, mas não são usadas no fluxo de segurar para falar.
 */
class SystemSttEngine(
    private val context: Context,
    private val preferOnDevice: Boolean,
) : SttEngine {

    private val manager = SpeechRecognizerManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var readyCb: (() -> Unit)? = null
    private var finalCb: ((String) -> Unit)? = null
    private var errorCb: ((String) -> Unit)? = null
    private var doneCb: (() -> Unit)? = null

    /**
     * O reconhecedor pode entregar o resultado antes de o usuário soltar o
     * botão (fim do áudio). Nesse caso `doneCb` ainda é null; guardamos que
     * terminou para o `stop()` posterior não ficar esperando um callback que
     * já passou (PTT preso em "Transcrevendo").
     */
    private var completed = false

    init {
        scope.launch {
            manager.state.collect { state ->
                when (state) {
                    VoiceInputState.Listening -> readyCb?.invoke()
                    is VoiceInputState.Result -> {
                        completed = true
                        finalCb?.invoke(state.text)
                        finish()
                    }
                    is VoiceInputState.Error -> {
                        completed = true
                        errorCb?.invoke(state.message)
                        finish()
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Última escuta usou o reconhecedor no aparelho (sem rede). */
    val lastUsedOnDevice: Boolean
        get() = manager.lastUsedOnDevice

    override val modelState: SttEngine.ModelState
        get() = if (isAvailable()) SttEngine.ModelState.READY else SttEngine.ModelState.ERROR

    override val modelSizeBytes: Long = 0L

    override fun start(
        onReady: () -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        readyCb = onReady
        finalCb = onFinal
        errorCb = onError
        doneCb = null
        completed = false
        manager.start(preferOnDevice = preferOnDevice)
    }

    override fun pause() {
        doneCb = null
        manager.stop()
    }

    override fun resume() {
        manager.start(preferOnDevice = preferOnDevice)
    }

    override fun stop(onDone: (() -> Unit)?) {
        if (completed) {
            // Resultado já chegou enquanto o botão ainda estava pressionado.
            completed = false
            onDone?.invoke()
            return
        }
        doneCb = onDone
        manager.finish()
    }

    override fun cancel() {
        completed = false
        doneCb = null
        manager.cancel()
    }

    override fun release() {
        readyCb = null
        finalCb = null
        errorCb = null
        doneCb = null
        completed = false
        scope.cancel()
        manager.destroy()
    }

    override fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    private fun finish() {
        val cb = doneCb
        doneCb = null
        cb?.invoke()
    }
}
