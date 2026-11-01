package com.bettertalker.app.data.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File

/**
 * STT engine baseado em sherpa-onnx ASR (NeMo CTC pt-BR int8).
 *
 * Status: SCAFFOLD — bindings vendorizados, modelo sob demanda.
 * Ainda não integrado ao SessionSpeechRecognizer.
 *
 * Modelo: 125 MB (98,5 MB comprimido), licença CC-BY-NC-4.0.
 * Destino: filesDir/stt/ (app-private).
 */
class SherpaSttEngine(private val context: Context) : SttEngine {

    private val tag = "SherpaStt"
    private val modelDir = File(context.filesDir, "stt")
    private val modelFile = File(modelDir, "model.int8.onnx")
    private val tokensFile = File(modelDir, "tokens.txt")

    private var recognizer: OfflineRecognizer? = null
    private var stream: OfflineStream? = null

    override val modelState: SttEngine.ModelState
        get() = when {
            !modelFile.exists() || !tokensFile.exists() -> SttEngine.ModelState.NOT_DOWNLOADED
            recognizer != null -> SttEngine.ModelState.READY
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
        if (!isAvailable()) {
            onError("Modelo STT não baixado. Baixe em Configurações → Voz.")
            return
        }
        try {
            if (recognizer == null) {
                loadModel()
            }
            stream = recognizer?.createStream()
            Log.i(tag, "SherpaSttEngine started")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start: ${e.message}")
            onError("Falha ao iniciar STT: ${e.message}")
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

    override fun pause() {
        // sherpa-onnx offline recognizer doesn't support pause mid-decode
    }

    override fun resume() {
        // no-op for offline
    }

    override fun stop() {
        try {
            stream?.let { s ->
                recognizer?.decode(s)
                val result = recognizer?.getResult(s)
                result?.text?.let { text ->
                    if (text.isNotBlank()) {
                        Log.i(tag, "Transcript: $text")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Stop error: ${e.message}")
        } finally {
            stream?.release()
            stream = null
        }
    }

    override fun cancel() {
        stream?.release()
        stream = null
    }

    override fun release() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
    }
}
