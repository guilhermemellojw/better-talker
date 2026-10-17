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
 * Modelo e espeak-ng-data esperados em filesDir/piper/jeff (provisionados
 * dos assets pelo PiperModelStore) — se faltar, o teste falha com mensagem
 * clara (não crasha: ensureLoaded devolve false).
 */
@RunWith(AndroidJUnit4::class)
class PiperPlaybackTest {

    private val tag = "PiperPlayback"

    @Test
    fun speakCompletaSemCrash() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "piper/jeff")
        assertTrue("modelo ausente: ${dir.absolutePath}", File(dir, "model.onnx").exists())
        assertTrue(
            "espeak ausente: ${File(dir, "espeak-ng-data").absolutePath}",
            File(dir, "espeak-ng-data").exists(),
        )

        val engine = PiperTtsManager(dir)
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

    /** Réplica do Ensaio: texto longo (~600 chars) deve INICIAR o áudio. */
    @Test
    fun speakTextoLongoInicia() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "piper/jeff")
        val engine = PiperTtsManager(dir)
        engine.warmUp()
        val t0 = System.currentTimeMillis()
        while (!engine.ready.value && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(200)
        assertTrue("Piper não ficou ready", engine.ready.value)

        val paragrafo = "Quando olhamos para a criação, vemos a assinatura de um " +
            "Deus de organização. As estações chegam no tempo certo, as aves " +
            "encontram o caminho de volta e tudo funciona segundo um padrão. "
        val texto = paragrafo.repeat(6)
        Log.i(tag, "texto longo: ${texto.length} chars")
        engine.speak(texto)
        val t1 = System.currentTimeMillis()
        while (!engine.speaking.value && System.currentTimeMillis() - t1 < 20_000) {
            Thread.sleep(100)
        }
        assertTrue("speak longo nunca iniciou", engine.speaking.value)
        Log.i(tag, "longo iniciou em ${System.currentTimeMillis() - t1}ms")
        Thread.sleep(3_000)
        engine.stop()
        assertTrue("stop não zerou speaking", !engine.speaking.value)
        engine.shutdown()
    }

    /** Fluxo EXATO do Ensaio: StageViewModel + nota real + piper injetado. */
    @Test
    fun ensaioDoViewModelFala() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "piper/jeff")
        val engine = PiperTtsManager(dir)
        engine.warmUp()
        val t0 = System.currentTimeMillis()
        while (!engine.ready.value && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(200)
        assertTrue("Piper não ficou ready", engine.ready.value)

        val db = com.bettertalker.app.data.db.DbProvider.get(ctx)
        val settings = com.bettertalker.app.data.prefs.SettingsStore(ctx)
        val noteId = "note-215a8a37-e519-4c01-a11a-ca204d8b0cd8" // N.º 35 (com conteúdo)
        val vm = com.bettertalker.app.ui.stage.StageViewModel(db, settings, noteId, ctx, engine)

        val t1 = System.currentTimeMillis()
        while (vm.content.value.isBlank() && System.currentTimeMillis() - t1 < 10_000) {
            Thread.sleep(200)
        }
        Log.i(tag, "conteudo: ${vm.content.value.length} chars (${System.currentTimeMillis() - t1}ms)")
        assertTrue("conteúdo do Ensaio vazio", vm.content.value.isNotBlank())

        vm.toggleSpeech()
        val t2 = System.currentTimeMillis()
        while (!vm.ttsSpeaking.value && System.currentTimeMillis() - t2 < 20_000) {
            Thread.sleep(100)
        }
        assertTrue("Ensaio (VM) nunca começou a falar", vm.ttsSpeaking.value)
        Log.i(tag, "ensaio iniciou em ${System.currentTimeMillis() - t2}ms")
        Thread.sleep(3_000)
        vm.toggleSpeech() // stop
        assertTrue("stop não zerou speaking", !vm.ttsSpeaking.value)
        engine.shutdown()
    }
}
