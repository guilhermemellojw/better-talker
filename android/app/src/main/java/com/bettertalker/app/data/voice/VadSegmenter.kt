package com.bettertalker.app.data.voice

/**
 * VAD (Voice Activity Detection) simples por energia (RMS).
 *
 * Segmenta o áudio em blocos de fala separados por silêncio.
 * Cada bloco é um segmento independente para decodificação.
 */
class VadSegmenter(
    /** Limiar de RMS para considerar "fala" (0..1). */
    private val speechThreshold: Float = 0.01f,
    /** Duração mínima de silêncio para cortar segmento (ms). */
    private val silenceDurationMs: Int = 800,
    /** Duração máxima de um segmento (ms). */
    private val maxSegmentMs: Int = 30_000,
    /** Sample rate. */
    private val sampleRate: Int = AudioCapture.SAMPLE_RATE,
) {
    private val silenceSamples = silenceDurationMs * sampleRate / 1000
    private val maxSegmentSamples = maxSegmentMs * sampleRate / 1000

    private val currentSegment = mutableListOf<Float>()
    private var silenceCount = 0
    private var hadSpeech = false
    private var speechSampleCount = 0
    private var segmentCount = 0

    /** Lista de segmentos completos (cada um é um FloatArray de áudio). */
    val segments = mutableListOf<FloatArray>()

    /** Alimenta um chunk de áudio. Retorna true se um segmento foi finalizado. */
    fun feed(chunk: FloatArray): Boolean {
        var finalized = false
        for (sample in chunk) {
            currentSegment.add(sample)
            val rms = kotlin.math.abs(sample)
            if (rms < speechThreshold) {
                silenceCount++
            } else {
                silenceCount = 0
                hadSpeech = true
                speechSampleCount++
            }
            // Finaliza segmento se silêncio suficiente ou tamanho máximo
            if (currentSegment.size >= maxSegmentSamples ||
                (silenceCount >= silenceSamples && currentSegment.size > sampleRate / 2)
            ) {
                if (hadSpeech && speechSampleCount > sampleRate / 4) { // pelo menos 250ms de áudio
                    segments.add(currentSegment.toFloatArray())
                    segmentCount++
                    finalized = true
                }
                currentSegment.clear()
                silenceCount = 0
                hadSpeech = false
                speechSampleCount = 0
            }
        }
        return finalized
    }

    /** Finaliza o segmento atual (chamado ao parar). */
    fun flush() {
        if (hadSpeech && speechSampleCount > sampleRate / 4) {
            segments.add(currentSegment.toFloatArray())
            segmentCount++
        }
        currentSegment.clear()
        silenceCount = 0
        hadSpeech = false
        speechSampleCount = 0
    }

    /** Limpa todos os segmentos. */
    fun reset() {
        currentSegment.clear()
        silenceCount = 0
        hadSpeech = false
        speechSampleCount = 0
        segments.clear()
        segmentCount = 0
    }

    /** Número de segmentos finalizados. */
    val segmentSize: Int get() = segments.size
}
