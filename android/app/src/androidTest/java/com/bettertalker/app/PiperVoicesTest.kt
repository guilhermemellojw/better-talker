package com.bettertalker.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * #23-bench-piper: gera 1 WAV por voz pt-BR do Piper (7 vozes). DESCARTÁVEL.
 * Estrutura em filesDir/piper_voices/{voz}/model.onnx + tokens.txt.
 * espeak-ng-data reusado de filesDir/kokoro-pt (mesmo pacote).
 */
@RunWith(AndroidJUnit4::class)
class PiperVoicesTest {

    private val tag = "PiperBench"

    @Test
    fun saveAllPiperVoices() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val baseDir = File(ctx.filesDir, "piper_voices")
        val dataDir = File(ctx.filesDir, "kokoro-pt/espeak-ng-data")
        val outDir = ctx.getExternalFilesDir(null)
        val frase = "Boa noite, irmãos. Hoje vamos considerar o exemplo de " +
            "Baruque, um homem que serviu a Jeová e aprendeu a ter esperança " +
            "mesmo em tempos difíceis."

        val vozes = listOf("faber", "jeff", "cadu", "edresson", "miro", "dii", "razo")
        for (nome in vozes) {
            val dir = File(baseDir, nome)
            val modelo = File(dir, "model.onnx")
            val tokens = File(dir, "tokens.txt")
            if (!modelo.exists() || !tokens.exists()) {
                Log.w(tag, "$nome: AUSENTE (${modelo.absolutePath})")
                continue
            }
            try {
                val tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = modelo.absolutePath,
                                tokens = tokens.absolutePath,
                                dataDir = dataDir.absolutePath,
                            ),
                            numThreads = 2,
                            debug = false,
                            provider = "cpu",
                        ),
                    ),
                )
                val sr = tts.sampleRate()
                val a = System.currentTimeMillis()
                val audio = tts.generate(frase, sid = 0, speed = 1.0f)
                val elapsed = System.currentTimeMillis() - a
                val dur = audio.samples.size / sr.toDouble()
                val out = File(outDir, "piper_$nome.wav")
                audio.save(out.absolutePath)
                Log.i(
                    tag,
                    String.format(
                        Locale.US,
                        "%s: elapsed=%dms, dur=%.2fs, RTF=%.3f, sr=%d, saved=%s (%d bytes)",
                        nome, elapsed, dur, elapsed / (dur * 1000.0), sr,
                        out.absolutePath, out.length(),
                    ),
                )
                assertTrue("WAV vazio: $nome", out.length() > 1000)
                tts.release()
            } catch (e: Exception) {
                Log.e(tag, "$nome: FALHOU — ${e.message}")
            }
        }
    }
}
