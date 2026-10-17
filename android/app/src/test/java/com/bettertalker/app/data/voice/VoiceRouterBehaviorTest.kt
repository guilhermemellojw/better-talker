package com.bettertalker.app.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #23-fix-a: roteamento Piper↔nativo + fallback (fakes puros). */
class VoiceRouterBehaviorTest {

    private class FakeEngine(readyNow: Boolean) : VoiceEngine {
        val spoken = mutableListOf<String>()
        var warmed = false
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
        override val ready: StateFlow<Boolean> = MutableStateFlow(readyNow)
        override val initialized: StateFlow<Boolean> = MutableStateFlow(true)
        override fun speak(text: String, rate: Float, pitch: Float) {
            spoken += text
        }
        override fun stop() {}
        override fun isSpeaking(): Boolean = false
        override fun warmUp() { warmed = true }
        override fun shutdown() {}
    }

    @Test
    fun premiumDesligadoVaiParaNativo() {
        val native = FakeEngine(true)
        val piper = FakeEngine(true)
        val r = VoiceRouter(native, piper, File("/nao-existe"))
        r.speak("x")
        assertEquals(listOf("x"), native.spoken)
        assertTrue(piper.spoken.isEmpty())
    }

    @Test
    fun premiumLigadoPiperProntoVaiParaPiper() {
        val native = FakeEngine(true)
        val piper = FakeEngine(true)
        val r = VoiceRouter(native, piper, File("/nao-existe"))
        r.setPremiumEnabled(true)
        r.speak("y")
        assertEquals(listOf("y"), piper.spoken)
        assertTrue(native.spoken.isEmpty())
    }

    @Test
    fun premiumLigadoPiperNaoProntoCaiNoNativo() {
        val native = FakeEngine(true)
        val piper = FakeEngine(false)
        val r = VoiceRouter(native, piper, File("/nao-existe"))
        r.setPremiumEnabled(true)
        r.speak("z")
        assertEquals(listOf("z"), native.spoken)
        assertTrue(piper.spoken.isEmpty())
    }

    @Test
    fun ligarPremiumPreCarregaOPiper() {
        // Regressão do deadlock: sem warmUp, o lazy nunca carregava e o
        // router caía no nativo para sempre.
        val native = FakeEngine(true)
        val piper = FakeEngine(false)
        val r = VoiceRouter(native, piper, File("/nao-existe"))
        r.setPremiumEnabled(true)
        assertTrue(piper.warmed)
        r.setPremiumEnabled(false)
    }

    @Test
    fun ensurePiperOrFallbackDesligaQuandoModeloAusente() {
        val r = VoiceRouter(FakeEngine(true), FakeEngine(false), File("/nao-existe"))
        r.setPremiumEnabled(true)
        r.ensurePiperOrFallback()
        assertFalse(r.premiumEnabled.value)
    }

    @Test
    fun ensurePiperOrFallbackMantemQuandoModeloPresente() {
        val dir = File.createTempFile("piper", "dir").let { it.delete(); it.mkdirs(); it }
        try {
            File(dir, "model.onnx").writeText("x")
            val r = VoiceRouter(FakeEngine(true), FakeEngine(false), dir)
            r.setPremiumEnabled(true)
            r.ensurePiperOrFallback()
            assertTrue(r.premiumEnabled.value)
        } finally {
            dir.deleteRecursively()
        }
    }
}
