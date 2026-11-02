package com.bettertalker.app.data.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Captura de áudio 16 kHz mono 16-bit PCM para STT.
 *
 * Usa AudioRecord com buffer mínimo. O áudio é acumulado em memória
 * (sem gravação em disco). Thread dedicada de captura.
 */
class AudioCapture {

    private val tag = "AudioCapture"
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false

    /** Callback para cada chunk de áudio capturado. */
    var onAudioChunk: ((ShortArray, Int) -> Unit)? = null

    /**
     * Inicia a captura. O chamador deve ter RECORD_AUDIO concedido
     * (SherpaSttEngine checa antes de abrir o microfone).
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (isCapturing) return
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(tag, "Invalid buffer size")
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2,
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(tag, "AudioRecord not initialized")
            audioRecord?.release()
            audioRecord = null
            return
        }
        isCapturing = true
        audioRecord?.startRecording()
        captureThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    onAudioChunk?.invoke(buffer.copyOf(read), read)
                }
            }
        }.apply { start() }
        Log.i(tag, "Capture started: ${bufferSize}B buffer, ${sampleRate}Hz")
    }

    /** Para a captura. */
    fun stop() {
        isCapturing = false
        captureThread?.join(500)
        captureThread = null
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
        Log.i(tag, "Capture stopped")
    }

    /** Converte ShortArray para FloatArray (normalizado -1..1). */
    fun shortsToFloats(shorts: ShortArray, count: Int): FloatArray {
        val floats = FloatArray(count)
        for (i in 0 until count) {
            floats[i] = shorts[i] / 32768.0f
        }
        return floats
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
