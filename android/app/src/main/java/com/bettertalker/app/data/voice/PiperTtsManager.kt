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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * v2.7.1 (crash nativo): o OfflineTts do sherpa NÃO é thread-safe.
     * `stop()` cancela a coroutine, mas a chamada JNI em andamento não é
     * interrompível; um novo `speak()` chamava `generateWithConfigAndCallback`
     * no mesmo objeto em paralelo → SIGSEGV em generateWithConfigImpl.
     * O mutex serializa geração e release do modelo.
     */
    private val nativeLock = Mutex()

    /** Invalida callbacks da geração anterior mesmo se um novo speak começar. */
    private val generation = AtomicLong(0)

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
        val enDict = dataDir?.let { File(it, "en_dict") }
        if (!modelFile.exists() || !tokensFile.exists() || dataDir == null ||
            enDict?.exists() != true
        ) {
            Log.w(
                "PiperTts",
                "indisponível: model=${modelFile.exists()} tokens=${tokensFile.exists()} " +
                    "espeak=${dataDir != null} en_dict=${enDict?.exists() == true}",
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
        val myGeneration = generation.incrementAndGet()
        playJob = scope.launch {
          nativeLock.withLock {
            if (generation.get() != myGeneration) return@withLock
            if (!ensureLoaded()) return@withLock
            val local = tts ?: return@withLock
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
                // #27-fix (P0): síntese POR BLOCO — o sintetizador nativo
                // estoura (SIGSEGV em generateWithConfigImpl) com o texto
                // inteiro do Ensaio (~30k chars). Blocos de frase no MESMO
                // AudioTrack = reprodução contínua + pausa entre blocos.
                for (chunk in chunkForTts(text)) {
                    if (generation.get() != myGeneration) break
                    var stopped = false
                    local.generateWithConfigAndCallback(
                        text = chunk,
                        config = GenerationConfig(speed = 1.0f, sid = 0),
                        // SherpaTtsCallback: o JNI exige invoke([F)Ljava/lang/Integer;
                        // (lambda Kotlin = primitivo → NoSuchMethodError → SIGABRT).
                        callback = SherpaTtsCallback { samples ->
                            if (generation.get() == myGeneration) {
                                // Convenção do sherpa: 0 = PARAR; != 0 = continuar.
                                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                                1
                            } else {
                                stopped = true
                                0
                            }
                        },
                    )
                    if (stopped) break
                }
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
                if (generation.get() == myGeneration) _speaking.value = false
            }
          }
        }
    }

    override fun stop() {
        // Invalida o callback da geração em andamento ANTES de cancelar:
        // a chamada JNI não é interrompível, mas para no próximo chunk.
        generation.incrementAndGet()
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
        scope.launch { nativeLock.withLock { ensureLoaded() } }
    }

    override fun shutdown() {
        // Mesma corrida do stop: o release do modelo espera o writer terminar.
        val job = playJob
        stop()
        scope.launch {
            runCatching { job?.join() }
            nativeLock.withLock {
                runCatching { tts?.release() }
                tts = null
            }
            scope.cancel()
        }
    }
}

/**
 * #27-fix (P0): divide o texto em blocos de frase (≤ [maxChars]) para a
 * síntese sequencial — o sintetizador nativo do sherpa estoura (SIGSEGV)
 * com texto gigante (o Ensaio manda o discurso inteiro). Nunca perde
 * conteúdo: blocos maiores que o teto são cortados em espaços. Puro/testável.
 */
internal fun chunkForTts(text: String, maxChars: Int = 400): List<String> {
    if (text.isBlank()) return emptyList()
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    fun flush() {
        val t = sb.toString().trim()
        if (t.isNotEmpty()) out += t
        sb.clear()
    }
    for (block in text.split('\n')) {
        val clean = block.trim()
        if (clean.isEmpty()) {
            flush()
            continue
        }
        for (sentence in Regex("(?<=[.!?…:;])\\s+").split(clean)) {
            val s = sentence.trim()
            if (s.isEmpty()) continue
            if (s.length > maxChars) {
                flush()
                var rest = s
                while (rest.length > maxChars) {
                    val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
                    out += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) sb.append(rest)
            } else {
                if (sb.isNotEmpty() && sb.length + s.length + 1 > maxChars) flush()
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
        }
    }
    flush()
    return out
}
