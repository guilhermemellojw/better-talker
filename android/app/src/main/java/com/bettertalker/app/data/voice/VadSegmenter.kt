package com.bettertalker.app.data.voice

/**
 * VAD (Voice Activity Detection) simples por energia (RMS).
 *
 * Segmenta o áudio em blocos de fala separados por silêncio.
 * Cada bloco é um segmento independente para decodificação.
 *
 * Memória: não acumula segmentos; [feed] devolve o segmento finalizado
 * (ou null). O chamador é responsável por consumir/descartar.
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

    /**
     * Alimenta um chunk de áudio. Retorna o segmento finalizado (se houver).
     * O segmento é removido da memória interna.
     */
    fun feed(chunk: FloatArray): FloatArray? {
        var finalized: FloatArray? = null
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
                if (hadSpeech && speechSampleCount > sampleRate / 4) {
                    finalized = currentSegment.toFloatArray()
                }
                currentSegment.clear()
                silenceCount = 0
                hadSpeech = false
                speechSampleCount = 0
                if (finalized != null) break
            }
        }
        return finalized
    }

    /**
     * Finaliza o segmento atual (chamado ao parar/pausar).
     * Retorna o segmento pendente (se houver fala suficiente).
     */
    fun flush(): FloatArray? {
        val out = if (hadSpeech && speechSampleCount > sampleRate / 4) {
            currentSegment.toFloatArray()
        } else null
        currentSegment.clear()
        silenceCount = 0
        hadSpeech = false
        speechSampleCount = 0
        return out
    }

    /** Limpa o estado sem produzir segmento. */
    fun reset() {
        currentSegment.clear()
        silenceCount = 0
        hadSpeech = false
        speechSampleCount = 0
    }
}
