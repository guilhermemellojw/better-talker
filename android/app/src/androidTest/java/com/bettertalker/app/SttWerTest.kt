package com.bettertalker.app

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.WaveReader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * WER humano — medição de qualidade do STT local (sherpa-onnx NeMo CTC pt-BR).
 *
 * Preparação (no aparelho, sem root):
 *   1. grave 3 amostras de 30–60 s (fala clara, rápida, baixa/ruído);
 *   2. converta para WAV 16 kHz mono 16-bit;
 *   3. crie `ground_truth.txt` com uma linha por amostra: `nome|texto exato`;
 *   4. copie tudo para:
 *      /sdcard/Android/data/com.bettertalker.app.debug/files/stt-wer/
 *      (o modelo já deve estar em files/stt/ — Configurações → Voz → Baixar).
 *
 * Execução:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.bettertalker.app.SttWerTest
 *
 * Saída (logcat, tag SttWer):
 *   LOAD|load_ms=…
 *   RESULT|nome|wer=0.xxx|rtf=0.xxx|elapsed_ms=…|dur=…|text=…
 *   SUMMARY|avg_wer=…|max_wer=…|avg_rtf=…
 *
 * NÃO faz parte do app final: é teste instrumentado.
 */
@RunWith(AndroidJUnit4::class)
class SttWerTest {

    private val tag = "SttWer"

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áàâãéêíóôõúüç ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun words(s: String): List<String> =
        normalize(s).split(' ').filter { it.isNotBlank() }

    /** WER por distância de Levenshtein de palavras. */
    fun wer(ref: String, hyp: String): Double {
        val r = words(ref)
        val h = words(hyp)
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        val dp = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) dp[i][0] = i
        for (j in 0..h.size) dp[0][j] = j
        for (i in 1..r.size) {
            for (j in 1..h.size) {
                val cost = if (r[i - 1] == h[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[r.size][h.size].toDouble() / r.size
    }

    private fun mem(label: String) {
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1024 / 1024
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        Log.i(
            tag,
            "MEM|$label|java=${javaMb}MB|native_alloc=${nativeMb}MB|pss=${mi.totalPss / 1024}MB",
        )
    }

    @Test
    fun medeWerDasAmostrasHumanas() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(ctx.filesDir, "stt/model.int8.onnx")
        val tokens = File(ctx.filesDir, "stt/tokens.txt")
        val root = File(ctx.getExternalFilesDir(null), "stt-wer")
        val truthFile = File(root, "ground_truth.txt")

        assertTrue("modelo ausente: ${model.absolutePath}", model.exists())
        assertTrue("tokens ausente: ${tokens.absolutePath}", tokens.exists())
        assertTrue("ground_truth.txt ausente: ${truthFile.absolutePath}", truthFile.exists())
        Log.i(tag, "model_mb=${model.length() / 1024 / 1024} root=${root.absolutePath}")

        val truth = truthFile.readLines()
            .filter { it.contains('|') }
            .associate { it.substringBefore('|').trim() to it.substringAfter('|').trim() }
        assertTrue("ground_truth vazio", truth.isNotEmpty())

        mem("before_load")
        val loadStart = System.currentTimeMillis()
        val recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            ),
        )
        val loadMs = System.currentTimeMillis() - loadStart
        Log.i(tag, "LOAD|load_ms=$loadMs")
        mem("after_load")

        var sumWer = 0.0
        var maxWer = 0.0
        var sumRtf = 0.0
        var n = 0
        try {
            for ((name, ref) in truth) {
                val wav = File(root, "$name.wav")
                if (!wav.exists()) {
                    Log.w(tag, "SKIP|$name|wav ausente")
                    continue
                }
                val wave = WaveReader.readWave(wav.absolutePath)
                val dur = wave.samples.size.toDouble() / wave.sampleRate
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(wave.samples, wave.sampleRate)
                    val t0 = System.currentTimeMillis()
                    recognizer.decode(stream)
                    val elapsed = System.currentTimeMillis() - t0
                    val text = recognizer.getResult(stream).text
                    val rtf = elapsed / (dur * 1000.0)
                    val w = wer(ref, text)
                    sumWer += w
                    maxWer = maxOf(maxWer, w)
                    sumRtf += rtf
                    n++
                    Log.i(
                        tag,
                        String.format(
                            Locale.US,
                            "RESULT|%s|wer=%.3f|rtf=%.3f|elapsed_ms=%d|dur=%.2f|text=%s",
                            name, w, rtf, elapsed, dur, text.replace('\n', ' '),
                        ),
                    )
                    mem("after_$name")
                } finally {
                    stream.release()
                }
            }
        } finally {
            recognizer.release()
            mem("after_release")
        }

        assertTrue("nenhuma amostra executada", n > 0)
        Log.i(
            tag,
            String.format(
                Locale.US,
                "SUMMARY|samples=%d|avg_wer=%.3f|max_wer=%.3f|avg_rtf=%.3f",
                n, sumWer / n, maxWer, sumRtf / n,
            ),
        )
    }
}
