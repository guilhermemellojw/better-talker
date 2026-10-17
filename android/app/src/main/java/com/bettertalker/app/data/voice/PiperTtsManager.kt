package com.bettertalker.app.data.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * #23-fix-a: TTS premium — Piper pt-BR (VITS via sherpa-onnx), voz Jeff.
 *
 * - Carga LAZY no primeiro [speak] (o modelo tem ~63MB; não pesa o boot).
 * - Geração em STREAMING: `generateWithConfigAndCallback` entrega chunks
 *   PCM float → escritos no AudioTrack (MODE_STREAM) com backpressure.
 * - [stop] interrompe: o callback checa [speaking] e aborta; o track é
 *   pausado/liberado.
 * - Se o modelo não carregar (arquivo ausente/corrompido), [ready] fica
 *   falso e o [VoiceRouter] cai no nativo — degradação suave.
 *
 * dataDir (espeak-ng-data): vive em `modelDir/espeak-ng-data` (o
 * PiperModelStore copia o subset pt-BR dos assets). Sem ele, não tenta
 * carregar (evita crash nativo do sherpa).
 */
class PiperTtsManager(
    private val modelDir: File,
) : VoiceEngine {

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    override val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Guarda o ciclo de vida do AudioTrack: pause/flush (stop) × release (writer). */
    private val trackLock = Any()

    /** espeak-ng-data: só o modelDir provisionado (assets → files/piper/jeff). */
    internal fun resolveEspeakDir(): File? =
        File(modelDir, "espeak-ng-data").takeIf { it.exists() }

    /** Carrega o modelo (lazy, no 1º speak). Falso = router cai no nativo. */
    internal fun ensureLoaded(): Boolean {
        tts?.let { return true }
        val modelFile = File(modelDir, "model.onnx")
        val tokensFile = File(modelDir, "tokens.txt")
        val dataDir = resolveEspeakDir()
        if (!modelFile.exists() || !tokensFile.exists() || dataDir == null) {
            Log.w(
                "PiperTts",
                "indisponível: model=${modelFile.exists()} tokens=${tokensFile.exists()} espeak=${dataDir != null}",
            )
            _initialized.value = true
            return false
        }
        return try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        dataDir = dataDir.absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            tts = OfflineTts(config = config)
            _ready.value = true
            _initialized.value = true
            Log.i("PiperTts", "load ok: ${modelDir.name} (sr=${tts?.sampleRate()})")
            true
        } catch (e: Throwable) {
            Log.e("PiperTts", "load falhou: ${e.message}", e)
            _ready.value = false
            _initialized.value = true
            false
        }
    }

    override fun speak(text: String, rate: Float, pitch: Float) {
        // rate/pitch ainda não mapeados no Piper (lengthScale fica para o #23-fix-b).
        stop()
        if (text.isBlank()) return
        playJob = scope.launch {
            if (!ensureLoaded()) return@launch
            val local = tts ?: return@launch
            _speaking.value = true
            var track: AudioTrack? = null
            try {
                val sampleRate = local.sampleRate()
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(
                        AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_FLOAT,
                        ).coerceAtLeast(4096),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                synchronized(trackLock) { audioTrack = track }
                track.play()
                local.generateWithConfigAndCallback(
                    text = text,
                    config = GenerationConfig(speed = 1.0f, sid = 0),
                    // SherpaTtsCallback: o JNI exige invoke([F)Ljava/lang/Integer;
                    // (lambda Kotlin = primitivo → NoSuchMethodError → SIGABRT).
                    callback = SherpaTtsCallback { samples ->
                        if (_speaking.value) {
                            // Convenção do sherpa: 0 = PARAR; != 0 = continuar.
                            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                            1
                        } else {
                            0
                        }
                    },
                )
            } catch (e: Throwable) {
                Log.e("PiperTts", "speak falhou: ${e.message}", e)
            } finally {
                // Release SEMPRE na thread do writer, sob o lock (o stop de
                // outra thread faz pause/flush — nunca tocamos um track liberado).
                synchronized(trackLock) {
                    runCatching { track?.stop() }
                    runCatching { track?.release() }
                    if (audioTrack === track) audioTrack = null
                }
                _speaking.value = false
            }
        }
    }

    override fun stop() {
        _speaking.value = false
        // Mantém a referência do job para o shutdown poder esperar (join).
        playJob?.cancel()
        // pause()+flush() desbloqueia o write; sob o lock para nunca tocar
        // um track que o writer está liberando. O release fica no finally.
        synchronized(trackLock) {
            audioTrack?.let { runCatching { it.pause(); it.flush() } }
        }
    }

    override fun isSpeaking(): Boolean = _speaking.value

    /** #23-fix-a: pré-carrega o modelo em background (evita o race do lazy). */
    override fun warmUp() {
        scope.launch { ensureLoaded() }
    }

    override fun shutdown() {
        // Mesma corrida do stop: o release do modelo espera o writer terminar.
        val job = playJob
        stop()
        scope.launch {
            runCatching { job?.join() }
            runCatching { tts?.release() }
            tts = null
            scope.cancel()
        }
    }
}
