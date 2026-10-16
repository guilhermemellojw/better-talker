package com.bettertalker.app

import android.os.Debug
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
 * #23-bench: mede o RTF do Kokoro pt-BR (fp32, sid 42 = pf_dora) no device.
 * DESCARTÁVEL — só mede; nada liga na UI. Modelo esperado em
 * filesDir/kokoro-pt (push via adb + run-as cp).
 */
@RunWith(AndroidJUnit4::class)
class KokoroBenchTest {

    private val tag = "KokoroBench"
    private val sampleRate = 24000.0

    @Test
    fun measureRtf() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "kokoro-pt")
        for (f in listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data")) {
            assertTrue("AUSENTE: ${File(dir, f).absolutePath}", File(dir, f).exists())
        }

        // 1) LOAD do modelo.
        val t0 = System.currentTimeMillis()
        val config = OfflineTtsConfig(
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
        )
        val tts = OfflineTts(config = config)
        val loadMs = System.currentTimeMillis() - t0
        Log.i(
            tag,
            "load: ${loadMs}ms | sampleRate=${tts.sampleRate()} | numSpeakers=${tts.numSpeakers()}",
        )

        // 2) Warm-up + 3 frases medidas (curta / média / longa).
        val short = "Boa noite, irmãos. Sejam bem-vindos."
        val medium = "Quando olhamos para o céu numa noite estrelada, percebemos a " +
            "ordem e a organização que existem em toda a criação. Cada estrela, " +
            "cada planeta, cada galáxia segue um curso preciso, definido por leis " +
            "que revelam inteligência e propósito. Isso nos faz refletir sobre " +
            "quem está por trás de tanta organização."
        val long = "Você já parou para pensar em como a organização está presente " +
            "em tudo o que nos cerca? Desde os menores detalhes da natureza até " +
            "as maiores estruturas do universo, tudo funciona segundo um padrão. " +
            "As estações chegam e partem no tempo certo. As marés sobem e descem " +
            "seguindo a lua. As aves migram milhares de quilômetros e encontram o " +
            "caminho de volta. Nada disso acontece por acaso. Existe uma mente " +
            "por trás de toda essa ordem, e essa mente é Jeová. Quando observamos " +
            "a criação, vemos a assinatura de um Deus de organização. Ele não faz " +
            "nada de forma desleixada ou improvisada. Tudo tem propósito, tudo " +
            "tem lugar, tudo tem tempo. E nós, como servos desse Deus, somos " +
            "convidados a refletir essa mesma organização em nossa vida. Isso " +
            "significa cuidar bem do que temos, planejar com sabedoria e tratar " +
            "as pessoas com respeito e consideração. Significa também participar " +
            "ativamente da obra que ele está realizando hoje. A organização de " +
            "Jeová não é uma estrutura distante ou fria. Ela é formada por " +
            "pessoas que se amam e que trabalham juntas em união. Cada um tem seu " +
            "papel, cada um contribui com seus talentos, e juntos avançamos. Ao " +
            "longo dos anos, essa organização tem se adaptado e crescido, sempre " +
            "acompanhando o ritmo das mudanças do mundo. Novas ferramentas, novos " +
            "métodos, novas maneiras de alcançar as pessoas. Mas o objetivo " +
            "permanece o mesmo: ajudar as pessoas a conhecer a Jeová e a viver de " +
            "acordo com os seus padrões. Quando percebemos que fazemos parte de " +
            "algo tão grandioso, nossa motivação se renova. Não estamos sozinhos. " +
            "Não estamos trabalhando em vão. Cada conversa, cada visita, cada " +
            "estudo, cada oração faz parte de algo maior. Por isso, continue " +
            "avançando com a organização unida de Jeová. Mantenha o passo com " +
            "seus irmãos, apoie os que estão ao seu lado e confie na direção que " +
            "recebemos. O futuro que nos aguarda é maravilhoso, e vale cada " +
            "esforço que fazemos hoje. Que nunca percamos de vista o privilégio " +
            "que é servir lado a lado com milhões de pessoas que amam a Jeová. " +
            "Juntos, seguimos em frente, com fé, com alegria e com a certeza de " +
            "que a organização de Deus jamais falhará."

        run(tts, "warmup", short)

        val results = mutableListOf<String>()
        for ((label, text) in listOf("curta" to short, "media" to medium, "longa" to long)) {
            results += run(tts, label, text)
        }

        // 3) RAM (pico aproximado pós-longa).
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        Log.i(
            tag,
            "RAM: pss=${mi.totalPss / 1024}MB | javaHeap=${rt.totalMemory() / 1048576}MB" +
                "/${rt.maxMemory() / 1048576}MB",
        )
        Log.i(tag, "SUMMARY: " + results.joinToString(" || "))

        tts.release()
    }

    private fun run(tts: OfflineTts, label: String, text: String): String {
        val words = text.trim().split(Regex("\\s+")).size
        val a = System.currentTimeMillis()
        val audio = tts.generate(text = text, sid = 42, speed = 1.0f)
        val elapsed = System.currentTimeMillis() - a
        val durS = audio.samples.size / sampleRate
        val rtf = if (durS > 0) elapsed / (durS * 1000.0) else -1.0
        val line = String.format(
            Locale.US,
            "%s: %d words, elapsed=%dms, dur=%.2fs, RTF=%.3f",
            label, words, elapsed, durS, rtf,
        )
        Log.i(tag, line)
        assertTrue("samples vazios em '$label'", audio.samples.isNotEmpty())
        return line
    }
}
