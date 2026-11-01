package com.bettertalker.app.data.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * STT engine baseado em sherpa-onnx ASR (NeMo CTC pt-BR int8).
 *
 * Pipeline: AudioCapture (16 kHz mono) → VadSegmenter → decode por segmento
 * em thread única (OfflineRecognizer é batch e serial).
 *
 * - Modelo sob demanda em filesDir/stt/ (125 MB, CC-BY-NC-4.0).
 * - Callbacks (onPartial/onFinal/onError) postados na main thread.
 * - Fila limitada de segmentos (4) para não estourar memória.
 * - Áudio nunca é gravado em disco.
 */
class SherpaSttEngine(private val context: Context) : SttEngine {

    private val tag = "SherpaStt"
    private val main = Handler(Looper.getMainLooper())
    private val modelDir = File(context.filesDir, "stt")
    private val modelFile = File(modelDir, "model.int8.onnx")
    private val tokensFile = File(modelDir, "tokens.txt")

    private var recognizer: OfflineRecognizer? = null
    private val capture = AudioCapture()
    private var vad = VadSegmenter()

    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sherpa-decode").apply { isDaemon = true }
    }
    private val queueDepth = AtomicInteger(0)
    private val maxQueueDepth = 4

    private val capturing = AtomicBoolean(false)
    private var stopped = false

    private var onPartialCb: (String) -> Unit = {}
    private var onFinalCb: (String) -> Unit = {}
    private var onErrorCb: (String) -> Unit = {}

    private val transcript = StringBuilder()

    override val modelState: SttEngine.ModelState
        get() = when {
            modelFile.exists() && tokensFile.exists() -> SttEngine.ModelState.READY
            else -> SttEngine.ModelState.NOT_DOWNLOADED
        }

    override val modelSizeBytes: Long
        get() = if (modelFile.exists()) modelFile.length() else 0L

    override fun isAvailable(): Boolean = modelFile.exists() && tokensFile.exists()

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        onPartialCb = onPartial
        onFinalCb = onFinal
        onErrorCb = onError

        if (!isAvailable()) {
            postError("Modelo STT não baixado. Baixe em Configurações → Voz.")
            return
        }
        try {
            if (recognizer == null) loadModel()
            vad = VadSegmenter()
            transcript.clear()
            stopped = false
            capturing.set(true)

            capture.onAudioChunk = { shorts, count ->
                if (capturing.get()) {
                    val floats = capture.shortsToFloats(shorts, count)
                    val segment = vad.feed(floats)
                    if (segment != null) enqueueDecode(segment)
                }
            }
            capture.start()
            Log.i(tag, "Started: model=${modelFile.name}")
        } catch (e: Exception) {
            Log.e(tag, "Start failed: ${e.message}")
            postError("Falha ao iniciar STT: ${e.message}")
        }
    }

    private fun loadModel() {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = modelFile.absolutePath,
                ),
                tokens = tokensFile.absolutePath,
                numThreads = 1,
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(null, config)
        Log.i(tag, "Model loaded: ${modelFile.name}")
    }

    private fun enqueueDecode(segment: FloatArray) {
        if (queueDepth.get() >= maxQueueDepth) {
            Log.w(tag, "Decode queue full — dropping segment (${segment.size} samples)")
            return
        }
        queueDepth.incrementAndGet()
        decodeExecutor.execute {
            try {
                decodeSegment(segment)
            } catch (e: Exception) {
                Log.e(tag, "Decode failed: ${e.message}")
                postError("Falha ao transcrever um trecho.")
            } finally {
                queueDepth.decrementAndGet()
            }
        }
    }

    private fun decodeSegment(samples: FloatArray) {
        val rec = recognizer ?: return
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            if (text.isNotBlank()) {
                synchronized(transcript) {
                    if (transcript.isNotEmpty()) transcript.append(' ')
                    transcript.append(text)
                }
                val full = transcript.toString()
                main.post { onFinalCb(full) }
                Log.i(tag, "Segment decoded: ${text.length} chars")
            }
        } finally {
            stream.release()
        }
    }

    override fun pause() {
        capturing.set(false)
        capture.stop()
        drainVad()
    }

    override fun resume() {
        if (!isAvailable()) {
            postError("Modelo STT não baixado.")
            return
        }
        if (recognizer == null) {
            try {
                loadModel()
            } catch (e: Exception) {
                postError("Falha ao carregar o modelo: ${e.message}")
                return
            }
        }
        vad = VadSegmenter()
        stopped = false
        capturing.set(true)
        capture.onAudioChunk = { shorts, count ->
            if (capturing.get()) {
                val floats = capture.shortsToFloats(shorts, count)
                val segment = vad.feed(floats)
                if (segment != null) enqueueDecode(segment)
            }
        }
        capture.start()
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        capturing.set(false)
        capture.stop()
        drainVad()
        Log.i(tag, "Stopped: transcript=${transcript.length} chars")
    }

    private fun drainVad() {
        val pending = vad.flush()
        if (pending != null) enqueueDecode(pending)
    }

    override fun cancel() {
        stopped = true
        capturing.set(false)
        capture.stop()
        vad.reset()
        synchronized(transcript) { transcript.clear() }
        Log.i(tag, "Cancelled")
    }

    override fun release() {
        cancel()
        decodeExecutor.shutdown()
        recognizer?.release()
        recognizer = null
        Log.i(tag, "Released")
    }

    private fun postError(msg: String) {
        Log.e(tag, msg)
        main.post { onErrorCb(msg) }
    }
}
