package com.bettertalker.app.data.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v2.7.2: o Piper não pode carregar o espeak-ng-data sem o en_dict. */
class PiperTtsManagerTest {

    private fun tempDir(): File =
        File.createTempFile("pipertts", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun ensureLoadedFalhaSemEnDictSemTocarNoNative() {
        val dir = tempDir()
        File(dir, "model.onnx").writeBytes(ByteArray(16))
        File(dir, "tokens.txt").writeText("a 0\n")
        File(dir, "espeak-ng-data").mkdirs()

        val manager = PiperTtsManager(dir)

        assertFalse(manager.ensureLoaded())
        assertTrue(manager.initialized.value)
        assertFalse(manager.ready.value)
    }
}
