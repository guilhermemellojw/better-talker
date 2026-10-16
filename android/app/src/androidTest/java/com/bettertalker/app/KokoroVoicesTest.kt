package com.bettertalker.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * #23-bench-vozes: gera 3 WAVs (pf_dora=42, pm_alex=43, pm_santa=44) com a
 * MESMA frase, para ouvir no PC. DESCARTÁVEL — não toca UI/manager.
 * Modelo esperado em filesDir/kokoro-pt (push via adb + run-as cp).
 */
@RunWith(AndroidJUnit4::class)
class KokoroVoicesTest {

    private val tag = "KokoroVoices"
    private val sampleRate = 24000.0

    @Test
    fun saveThreeVoices() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "kokoro-pt")
        for (f in listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data")) {
            assertTrue("AUSENTE: ${File(dir, f).absolutePath}", File(dir, f).exists())
        }

        val t0 = System.currentTimeMillis()
        val tts = OfflineTts(
            assetManager = null,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(dir, "model.onnx").absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                        lang = "pt",
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
            ),
        )
        Log.i(tag, "load: ${System.currentTimeMillis() - t0}ms")

        // Nomes próprios (Baruque, Jeová) para testar pronúncia.
        val frase = "Boa noite, irmãos. Hoje vamos considerar o exemplo de " +
            "Baruque, um homem que serviu a Jeová e aprendeu a ter esperança " +
            "mesmo em tempos difíceis."

        val outDir = ctx.getExternalFilesDir(null)
        val vozes = listOf("pf_dora" to 42, "pm_alex" to 43, "pm_santa" to 44)
        for ((nome, sid) in vozes) {
            val a = System.currentTimeMillis()
            val audio = tts.generate(frase, sid = sid, speed = 1.0f)
            val elapsed = System.currentTimeMillis() - a
            val dur = audio.samples.size / sampleRate
            val rtf = elapsed / (dur * 1000.0)
            val out = File(outDir, "kokoro_$nome.wav")
            audio.save(out.absolutePath)
            Log.i(
                tag,
                String.format(
                    Locale.US,
                    "%s (sid=%d): elapsed=%dms, dur=%.2fs, RTF=%.3f, saved=%s (%d bytes)",
                    nome, sid, elapsed, dur, rtf, out.absolutePath, out.length(),
                ),
            )
            assertTrue("WAV vazio: ${out.absolutePath}", out.length() > 1000)
        }

        tts.release()
    }
}
