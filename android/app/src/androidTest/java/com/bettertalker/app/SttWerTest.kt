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
 *   MODEL|nome|wer=…|rtf=…|text=…      (WAV inteiro — qualidade do modelo)
 *   PIPELINE|nome|wer=…|rtf=…|text=…   (VAD + decode por segmento — caminho do app)
 *   SUMMARY|model_avg_wer=…|pipeline_avg_wer=…|…
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
        // Modelo: files/stt/ (baixado em Configurações → Voz).
        // Amostras: files/stt-wer/ (interno do app; o script copia via run-as).
        val model = File(ctx.filesDir, "stt/model.int8.onnx")
        val tokens = File(ctx.filesDir, "stt/tokens.txt")
        val root = File(ctx.filesDir, "stt-wer")
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

        var sumModel = 0.0
        var maxModel = 0.0
        var sumPipe = 0.0
        var maxPipe = 0.0
        var sumRtfModel = 0.0
        var sumRtfPipe = 0.0
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

                // MODEL: WAV inteiro (qualidade do modelo isolado).
                val (textModel, msModel) = decodeAll(recognizer, wave.samples, wave.sampleRate)
                // PIPELINE: chunks → VadSegmenter → decode por segmento (caminho do app).
                val (textPipe, msPipe) = decodePipeline(recognizer, wave.samples, wave.sampleRate)

                val rtfModel = msModel / (dur * 1000.0)
                val rtfPipe = msPipe / (dur * 1000.0)
                val wModel = wer(ref, textModel)
                val wPipe = wer(ref, textPipe)
                sumModel += wModel
                maxModel = maxOf(maxModel, wModel)
                sumPipe += wPipe
                maxPipe = maxOf(maxPipe, wPipe)
                sumRtfModel += rtfModel
                sumRtfPipe += rtfPipe
                n++
                Log.i(
                    tag,
                    String.format(
                        Locale.US,
                        "MODEL|%s|wer=%.3f|rtf=%.3f|elapsed_ms=%d|dur=%.2f|text=%s",
                        name, wModel, rtfModel, msModel, dur, textModel.replace('\n', ' '),
                    ),
                )
                Log.i(
                    tag,
                    String.format(
                        Locale.US,
                        "PIPELINE|%s|wer=%.3f|rtf=%.3f|elapsed_ms=%d|dur=%.2f|text=%s",
                        name, wPipe, rtfPipe, msPipe, dur, textPipe.replace('\n', ' '),
                    ),
                )
                mem("after_$name")
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
                "SUMMARY|samples=%d|model_avg_wer=%.3f|model_max_wer=%.3f|" +
                    "pipeline_avg_wer=%.3f|pipeline_max_wer=%.3f|" +
                    "model_avg_rtf=%.3f|pipeline_avg_rtf=%.3f",
                n, sumModel / n, maxModel, sumPipe / n, maxPipe,
                sumRtfModel / n, sumRtfPipe / n,
            ),
        )
    }

    /** Decodifica o WAV inteiro de uma vez. Retorna (texto, ms). */
    private fun decodeAll(
        rec: OfflineRecognizer,
        samples: FloatArray,
        sampleRate: Int,
    ): Pair<String, Long> {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            val t0 = System.currentTimeMillis()
            rec.decode(stream)
            val elapsed = System.currentTimeMillis() - t0
            rec.getResult(stream).text to elapsed
        } finally {
            stream.release()
        }
    }

    /**
     * Mesmo caminho do app: chunks de 1024 samples → VadSegmenter →
     * decode por segmento finalizado → concatenação. Retorna (texto, ms).
     */
    private fun decodePipeline(
        rec: OfflineRecognizer,
        samples: FloatArray,
        sampleRate: Int,
    ): Pair<String, Long> {
        val vad = com.bettertalker.app.data.voice.VadSegmenter(sampleRate = sampleRate)
        val sb = StringBuilder()
        var elapsed = 0L

        fun consume(segment: FloatArray?) {
            if (segment == null) return
            val (text, ms) = decodeAll(rec, segment, sampleRate)
            elapsed += ms
            if (text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(text.trim())
            }
        }

        val chunk = 1024
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + chunk, samples.size)
            consume(vad.feed(samples.copyOfRange(i, end)))
            i = end
        }
        consume(vad.flush())
        return sb.toString() to elapsed
    }
}
