package com.bettertalker.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.voice.PiperTtsManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #23-fix-b: playback REAL do Piper (AudioTrack + callback JNI) no device.
 * Modelo esperado em filesDir/piper/jeff (baixado pela Settings) e o
 * espeak-ng-data em filesDir/kokoro-pt (ponte) — se faltar, o teste falha
 * com mensagem clara (não crasha: ensureLoaded devolve false).
 */
@RunWith(AndroidJUnit4::class)
class PiperPlaybackTest {

    private val tag = "PiperPlayback"

    @Test
    fun speakCompletaSemCrash() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "piper/jeff")
        assertTrue("modelo ausente: ${dir.absolutePath}", File(dir, "model.onnx").exists())
        assertTrue("espeak ponte ausente", File(ctx.filesDir, "kokoro-pt/espeak-ng-data").exists())

        val engine = PiperTtsManager(ctx, dir)
        engine.warmUp()

        // Aguarda o load (até 20s).
        val t0 = System.currentTimeMillis()
        while (!engine.ready.value && System.currentTimeMillis() - t0 < 20_000) {
            Thread.sleep(200)
        }
        assertTrue("Piper não ficou ready (load falhou?)", engine.ready.value)
        Log.i(tag, "ready em ${System.currentTimeMillis() - t0}ms")

        // Fala e espera o ciclo completo (start → fim).
        engine.speak("Boa noite, irmãos. Este é o teste de áudio do Jeff no tablet.")
        val t1 = System.currentTimeMillis()
        var started = false
        while (System.currentTimeMillis() - t1 < 30_000) {
            if (engine.speaking.value) started = true
            if (started && !engine.speaking.value) break
            Thread.sleep(100)
        }
        val total = System.currentTimeMillis() - t1
        Log.i(tag, "ciclo: started=$started, total=${total}ms")
        assertTrue("speak nunca iniciou", started)
        assertTrue("speak não terminou em 30s", total < 30_000)

        engine.shutdown()
    }
}
