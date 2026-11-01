package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmenterTest {

    @Test
    fun segmentaFalaESilencio() {
        val vad = VadSegmenter(speechThreshold = 0.01f, silenceDurationMs = 100)
        // 1 segundo de fala (amostras altas)
        val speech = FloatArray(16000) { 0.5f }
        val s1 = vad.feed(speech)
        // 1 segundo de silêncio (amostras baixas)
        val silence = FloatArray(16000) { 0.001f }
        val s2 = vad.feed(silence)
        val pending = vad.flush()
        assertNotNull(s1 ?: s2 ?: pending)
    }

    @Test
    fun ignoraAudioMuitoCurto() {
        val vad = VadSegmenter(speechThreshold = 0.01f, silenceDurationMs = 100)
        // 100ms de fala (muito curto)
        val short = FloatArray(1600) { 0.5f }
        val s1 = vad.feed(short)
        val silence = FloatArray(16000) { 0.001f }
        val s2 = vad.feed(silence)
        val pending = vad.flush()
        assertNull(s1)
        assertNull(s2)
        assertNull(pending)
    }

    @Test
    fun resetLimpaEstado() {
        val vad = VadSegmenter()
        val speech = FloatArray(16000) { 0.5f }
        vad.feed(speech)
        vad.reset()
        assertNull(vad.flush())
    }

    @Test
    fun segmentoMaximo() {
        val vad = VadSegmenter(maxSegmentMs = 1000, silenceDurationMs = 5000)
        // 2 segundos de fala contínua (sem silêncio suficiente)
        val speech = FloatArray(32000) { 0.5f }
        val s1 = vad.feed(speech)
        val pending = vad.flush()
        assertTrue(s1 != null || pending != null)
    }

    @Test
    fun feedDevolveSegmentoEDrenagem() {
        val vad = VadSegmenter(speechThreshold = 0.01f, silenceDurationMs = 100)
        val speech = FloatArray(16000) { 0.5f }
        val silence = FloatArray(16000) { 0.001f }
        val s1 = vad.feed(speech)
        val s2 = vad.feed(silence)
        val segment = s1 ?: s2 ?: vad.flush()
        assertNotNull(segment)
        assertEquals(0, vad.flush()?.size ?: 0)
    }
}
