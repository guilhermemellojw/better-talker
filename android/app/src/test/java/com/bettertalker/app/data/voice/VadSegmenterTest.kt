package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmenterTest {

    @Test
    fun segmentaFalaESilencio() {
        val vad = VadSegmenter(speechThreshold = 0.01f, silenceDurationMs = 100)
        // 1 segundo de fala (amostras altas)
        val speech = FloatArray(16000) { 0.5f }
        vad.feed(speech)
        // 1 segundo de silêncio (amostras baixas)
        val silence = FloatArray(16000) { 0.001f }
        vad.feed(silence)
        vad.flush()
        assertTrue(vad.segmentSize >= 1)
    }

    @Test
    fun ignoraAudioMuitoCurto() {
        val vad = VadSegmenter(speechThreshold = 0.01f, silenceDurationMs = 100)
        // 100ms de fala (muito curto)
        val short = FloatArray(1600) { 0.5f }
        vad.feed(short)
        val silence = FloatArray(16000) { 0.001f }
        vad.feed(silence)
        vad.flush()
        assertEquals(0, vad.segmentSize)
    }

    @Test
    fun resetLimpaSegmentos() {
        val vad = VadSegmenter()
        val speech = FloatArray(16000) { 0.5f }
        vad.feed(speech)
        vad.reset()
        assertEquals(0, vad.segmentSize)
    }

    @Test
    fun segmentoMaximo() {
        val vad = VadSegmenter(maxSegmentMs = 1000, silenceDurationMs = 5000)
        // 2 segundos de fala contínua (sem silêncio suficiente)
        val speech = FloatArray(32000) { 0.5f }
        vad.feed(speech)
        vad.flush()
        assertTrue(vad.segmentSize >= 1)
    }
}
