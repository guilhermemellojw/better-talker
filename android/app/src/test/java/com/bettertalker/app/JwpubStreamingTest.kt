package com.bettertalker.app

import com.bettertalker.app.data.util.ImportLimits
import com.bettertalker.app.data.util.JwpubExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** T1 — limites por tipo + leitura em streaming do JWPUB (sem `contents` em RAM). */
class JwpubStreamingTest {

    private fun tmpDir(): File = File(System.getProperty("java.io.tmpdir")!!)

    private fun outerZip(contentsBytes: ByteArray): File {
        val f = File.createTempFile("jwpub-outer", ".jwpub")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"publication":{"fileName":"x_T.db"}}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("contents"))
            z.write(contentsBytes)
            z.closeEntry()
        }
        return f
    }

    @Test
    fun limitesPorTipoDeArquivo() {
        assertEquals(10L * 1024 * 1024, ImportLimits.OUTLINE_BYTES)
        assertEquals(400L * 1024 * 1024, ImportLimits.LIBRARY_BYTES)
        assertEquals(400L * 1024 * 1024, JwpubExtractor.MAX_DB_BYTES)
        assertEquals(8_000_000, JwpubExtractor.MAX_TEXT)
    }

    @Test
    fun readOuterToStreamaContentsParaArquivo() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val src = outerZip(payload)
        try {
            val outer = JwpubExtractor.readOuterTo(src, tmpDir())
            assertTrue(outer.manifestJson.contains("x_T.db"))
            assertEquals(payload.size.toLong(), outer.contentsFile.length())
            assertTrue(outer.contentsFile.readBytes().contentEquals(payload))
            outer.contentsFile.delete()
        } finally {
            src.delete()
        }
    }

    @Test
    fun readOuterToRejeitaAcimaDoLimite() {
        val src = outerZip(ByteArray(64))
        try {
            JwpubExtractor.readOuterTo(src, tmpDir(), maxBytes = 10)
            fail("esperava JwpubException")
        } catch (e: JwpubExtractor.JwpubException) {
            assertTrue(e.message!!.contains("muito grande"))
        } finally {
            src.delete()
        }
    }

    @Test
    fun unzipDbExtraiBancoEPrespeitaLimite() {
        val inner = File.createTempFile("inner", ".zip")
        ZipOutputStream(inner.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("x_T.db"))
            z.write(ByteArray(1024) { 7 })
            z.closeEntry()
        }
        val dst = File(tmpDir(), "out-${System.currentTimeMillis()}.db")
        try {
            JwpubExtractor.unzipDb(inner, "x_T.db", dst)
            assertEquals(1024L, dst.length())
            dst.delete()
            try {
                JwpubExtractor.unzipDb(inner, "x_T.db", dst, maxBytes = 10)
                fail("esperava JwpubException")
            } catch (e: JwpubExtractor.JwpubException) {
                assertTrue(e.message!!.contains("Banco interno muito grande"))
            } finally {
                dst.delete()
            }
        } finally {
            inner.delete()
        }
    }
}
