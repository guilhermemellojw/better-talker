package com.bettertalker.app.data.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #23-fix-a: download do modelo Piper (fetch fake — sem rede). */
class PiperModelStoreTest {

    private fun tempDir(): File =
        File.createTempFile("piperstore", "").let { it.delete(); it.mkdirs(); it }

    private fun fakeFetch(
        ok: Boolean = true,
        bytes: Int = 2_000_000,
    ): suspend (String, File, (Float) -> Unit) -> Unit = { _, dest, onProgress ->
        if (!ok) error("rede caiu")
        dest.writeBytes(ByteArray(bytes))
        onProgress(1f)
    }

    @Test
    fun estadoInicialNaoBaixado() {
        val store = PiperModelStore(tempDir(), fakeFetch())
        assertFalse(store.state.value.downloaded)
        assertFalse(store.state.value.downloading)
    }

    @Test
    fun downloadSucessoMarcaBaixado() = runBlocking {
        val dir = tempDir()
        val store = PiperModelStore(dir, fakeFetch())
        store.download()
        assertTrue(store.state.value.downloaded)
        assertFalse(store.state.value.downloading)
        assertNull(store.state.value.error)
        assertTrue(File(dir, "model.onnx").exists())
        assertTrue(File(dir, "tokens.txt").exists())
    }

    @Test
    fun falhaDeRedeMarcaErro() = runBlocking {
        val store = PiperModelStore(tempDir(), fakeFetch(ok = false))
        store.download()
        assertFalse(store.state.value.downloaded)
        assertNotNull(store.state.value.error)
    }

    @Test
    fun deleteRemoveArquivos() = runBlocking {
        val dir = tempDir()
        val store = PiperModelStore(dir, fakeFetch())
        store.download()
        assertTrue(store.state.value.downloaded)
        store.delete()
        assertFalse(store.state.value.downloaded)
        assertFalse(File(dir, "model.onnx").exists())
        assertFalse(File(dir, "tokens.txt").exists())
    }
}
