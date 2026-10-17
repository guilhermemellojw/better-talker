package com.bettertalker.app.data.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

/** #23-fix-b: provisionamento do Piper a partir dos assets (fake — sem Android). */
class PiperModelStoreTest {

    private fun tempDir(): File =
        File.createTempFile("piperstore", "").let { it.delete(); it.mkdirs(); it }

    private fun fakeAssets(): Map<String, ByteArray> {
        val model = ByteArray(1_000_001) // > 1MB: o gate do ready()
        val small = ByteArray(16)
        return mapOf(
            "piper/jeff/model.onnx" to model,
            "piper/jeff/tokens.txt" to ByteArray(8),
            "piper/jeff/espeak-ng-data/phondata" to small,
            "piper/jeff/espeak-ng-data/phonindex" to small,
            "piper/jeff/espeak-ng-data/phontab" to small,
            "piper/jeff/espeak-ng-data/intonations" to small,
            "piper/jeff/espeak-ng-data/pt_dict" to small,
            "piper/jeff/espeak-ng-data/lang/roa/pt" to small,
            "piper/jeff/espeak-ng-data/lang/roa/pt-BR" to small,
        )
    }

    private fun openAssetFrom(map: Map<String, ByteArray>): (String) -> InputStream =
        { path -> map[path]?.inputStream() ?: throw IOException("asset ausente: $path") }

    @Test
    fun estadoInicialNaoProvisionado() {
        val store = PiperModelStore(tempDir(), openAssetFrom(fakeAssets()))
        assertFalse(store.state.value.downloaded)
        assertFalse(store.state.value.downloading)
    }

    @Test
    fun ensureProvisionedCopiaDosAssets() = runBlocking {
        val dir = tempDir()
        val store = PiperModelStore(dir, openAssetFrom(fakeAssets()))
        store.ensureProvisioned()
        assertTrue(store.state.value.downloaded)
        assertFalse(store.state.value.downloading)
        assertNull(store.state.value.error)
        assertTrue(File(dir, "model.onnx").exists())
        assertTrue(File(dir, "tokens.txt").exists())
        assertTrue(File(dir, "espeak-ng-data/phondata").exists())
        assertTrue(File(dir, "espeak-ng-data/lang/roa/pt-BR").exists())
        // .part não sobra depois do rename.
        assertFalse(File(dir, "model.onnx.part").exists())
    }

    @Test
    fun readyExigeEspeakCompleto() = runBlocking {
        val dir = tempDir()
        val store = PiperModelStore(dir, openAssetFrom(fakeAssets()))
        store.ensureProvisioned()
        assertTrue(store.ready())
        // Subset quebrado (sem pt_dict) → não está pronto.
        File(dir, "espeak-ng-data/pt_dict").delete()
        assertFalse(store.ready())
    }

    @Test
    fun assetAusenteMarcaErro() = runBlocking {
        val store = PiperModelStore(tempDir(), openAssetFrom(emptyMap()))
        store.ensureProvisioned()
        assertFalse(store.state.value.downloaded)
        assertNotNull(store.state.value.error)
    }

    @Test
    fun deleteRemoveArquivos() = runBlocking {
        val dir = tempDir()
        val store = PiperModelStore(dir, openAssetFrom(fakeAssets()))
        store.ensureProvisioned()
        assertTrue(store.state.value.downloaded)
        store.delete()
        assertFalse(store.state.value.downloaded)
        assertFalse(File(dir, "model.onnx").exists())
        assertFalse(File(dir, "espeak-ng-data").exists())
    }
}
