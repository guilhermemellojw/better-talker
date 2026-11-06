package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlador PTT com motor fake (sem Android/Room). */
class PttControllerTest {

    private class FakeStt : SttEngine {
        var starts = 0
        var stops = 0
        var cancels = 0
        var releases = 0
        private var ready: (() -> Unit)? = null
        private var partial: ((String) -> Unit)? = null
        private var final: ((String) -> Unit)? = null
        private var error: ((String) -> Unit)? = null
        private var done: (() -> Unit)? = null

        override val modelState: SttEngine.ModelState = SttEngine.ModelState.READY
        override val modelSizeBytes: Long = 1L

        override fun start(
            onReady: () -> Unit,
            onPartial: (String) -> Unit,
            onFinal: (String) -> Unit,
            onError: (String) -> Unit,
        ) {
            starts++
            ready = onReady
            partial = onPartial
            final = onFinal
            error = onError
        }

        override fun pause() {}
        override fun resume() {}
        override fun stop(onDone: (() -> Unit)?) {
            stops++
            done = onDone
        }

        override fun cancel() {
            cancels++
        }

        override fun release() {
            releases++
        }

        override fun isAvailable(): Boolean = true

        fun readyNow() = ready?.invoke()
        fun partialNow(text: String) = partial?.invoke(text)
        fun finalNow(text: String) = final?.invoke(text)
        fun errorNow(message: String) = error?.invoke(message)
        fun doneNow() = done?.invoke()
    }

    private class FakeProvider(
        private val selection: PttSelection?,
        private val onRelease: () -> Unit = {},
    ) : PttEngineProvider {
        var created = 0
        var released = 0

        override fun create(): PttSelection? {
            created++
            return selection
        }

        override fun release() {
            released++
            onRelease()
        }
    }

    private fun controller(
        stt: FakeStt,
        onTranscript: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    ): PttController {
        val provider = FakeProvider(PttSelection(stt, PttSttChoice.LOCAL)) { stt.release() }
        return PttController(provider).apply {
            this.onTranscript = onTranscript
            this.onError = onError
        }
    }

    @Test
    fun press_solta_entregaTranscricaoEPensa() {
        val stt = FakeStt()
        var text = ""
        val c = controller(stt, onTranscript = { text = it })

        c.press()
        assertEquals(PttState.Starting, c.state.value)
        assertEquals(1, stt.starts)

        stt.readyNow()
        assertEquals(PttState.Listening, c.state.value)

        stt.partialNow("olá")
        assertEquals("olá", c.partial.value)
        stt.finalNow("olá")
        c.release()
        assertEquals(PttState.Transcribing, c.state.value)
        assertEquals(1, stt.stops)

        stt.doneNow()
        assertEquals(PttState.Thinking, c.state.value)
        assertEquals("olá", text)

        c.onResponseFinished(spoken = false)
        assertEquals(PttState.Idle, c.state.value)
    }

    @Test
    fun soltarAntesDeAbrirMicrofone_cancelaSemTranscrever() {
        val stt = FakeStt()
        var text = ""
        val c = controller(stt, onTranscript = { text = it })

        c.press()
        c.release()

        assertEquals(PttState.Idle, c.state.value)
        assertEquals(1, stt.cancels)
        assertEquals(0, stt.stops)
        assertEquals("", text)
    }

    @Test
    fun cancelarDescartaParcialENaoEntregaTexto() {
        val stt = FakeStt()
        var text = ""
        val c = controller(stt, onTranscript = { text = it })

        c.press()
        stt.readyNow()
        stt.finalNow("texto descartado")
        c.cancel()

        assertEquals(PttState.Idle, c.state.value)
        assertEquals(1, stt.cancels)
        assertEquals("", text)
        c.release()
        assertEquals(0, stt.stops)
    }

    @Test
    fun transcricaoVazia_viraErroHumano() {
        val stt = FakeStt()
        var error = ""
        val c = controller(stt, onError = { error = it })

        c.press()
        stt.readyNow()
        c.release()
        stt.doneNow()

        assertTrue(c.state.value is PttState.Error)
        assertTrue((c.state.value as PttState.Error).message.contains("Não entendi"))
        assertTrue(error.contains("Não entendi"))
    }

    @Test
    fun erroDoMotor_viraErroERetentativaAbreDeNovo() {
        val stt = FakeStt()
        var error = ""
        val c = controller(stt, onError = { error = it })

        c.press()
        stt.errorNow("Microfone ocupado")
        assertTrue(c.state.value is PttState.Error)
        assertEquals("Microfone ocupado", error)

        c.press()
        assertEquals(PttState.Starting, c.state.value)
        assertEquals(2, stt.starts)
    }

    @Test
    fun bargeInDuranteSpeakingParaTtsEVoltaAOuvir() {
        val stt = FakeStt()
        val c = controller(stt)

        c.press()
        stt.readyNow()
        stt.finalNow("primeira")
        c.release()
        stt.doneNow()
        c.onResponseFinished(spoken = true)
        assertEquals(PttState.Speaking, c.state.value)

        var stopped = false
        c.onStopSpeaking = { stopped = true }
        c.press()

        assertTrue(stopped)
        assertEquals(PttState.Starting, c.state.value)
        assertEquals(2, stt.starts)
    }

    @Test
    fun bargeInDuranteThinkingCancelaGeracaoAntesDeOuvir() {
        val stt = FakeStt()
        val c = controller(stt)

        c.press()
        stt.readyNow()
        stt.finalNow("primeira")
        c.release()
        stt.doneNow()
        assertEquals(PttState.Thinking, c.state.value)

        var stopped = false
        c.onStopThinking = { stopped = true }
        c.press()

        assertTrue(stopped)
        assertEquals(PttState.Starting, c.state.value)
    }

    @Test
    fun ttsFinalizaQuandoFalaTermina() {
        val stt = FakeStt()
        val c = controller(stt)

        c.press()
        stt.readyNow()
        stt.finalNow("resposta")
        c.release()
        stt.doneNow()
        c.onResponseFinished(spoken = true)
        assertEquals(PttState.Speaking, c.state.value)

        c.onSpeakingFinished()
        assertEquals(PttState.Idle, c.state.value)
    }

    @Test
    fun disposeLiberaMotorEProvider() {
        val stt = FakeStt()
        val provider = FakeProvider(PttSelection(stt, PttSttChoice.LOCAL)) { stt.release() }
        val c = PttController(provider)

        c.press()
        stt.readyNow()
        c.dispose()

        assertEquals(PttState.Idle, c.state.value)
        assertEquals(1, stt.cancels)
        assertEquals(1, stt.releases)
        assertEquals(1, provider.released)
    }

    @Test
    fun semMotor_disponivelViraErro() {
        var error = ""
        val c = PttController(FakeProvider(null)).apply { onError = { error = it } }

        c.press()

        assertTrue(c.state.value is PttState.Error)
        assertTrue(error.contains("indisponível"))
    }

    @Test
    fun escolhaDoMotorFicaVisivelParaUi() {
        val stt = FakeStt()
        val c = controller(stt)
        c.press()
        assertEquals(PttSttChoice.LOCAL, c.choice.value)
    }
}
