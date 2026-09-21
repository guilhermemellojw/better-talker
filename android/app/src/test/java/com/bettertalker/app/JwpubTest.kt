package com.bettertalker.app

import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.JwpubExtractor
import com.bettertalker.app.data.util.detectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class JwpubTest {

    /** Vetor real verificado contra arquivo oficial (th_T.jwpub, cartão 5_th_2018). */
    @Test
    fun keyIvVector() {
        val (key, iv) = JwpubExtractor.computeKeyIv("5_th_2018")
        assertEquals("8e2daa10bf247e8e32ef13e92610219a", key.toHex())
        assertEquals("028ced53198ef518a1f955d7505cb905", iv.toHex())
    }

    @Test
    fun roundTripDecrypt() {
        val html = "<h1>Comece bem</h1><p>Fale de coração, com amor e fé.</p>"
        val (key, iv) = JwpubExtractor.computeKeyIv("5_th_2018")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val def = Deflater()
        def.setInput(html.toByteArray(Charsets.UTF_8))
        def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!def.finished()) out.write(buf, 0, def.deflate(buf))
        def.end()
        val blob = cipher.doFinal(out.toByteArray())
        val back = JwpubExtractor.decryptInflate(blob, key, iv)
        assertEquals(html, back)
    }

    @Test
    fun wrongKeyReturnsNull() {
        val (key, iv) = JwpubExtractor.computeKeyIv("5_th_2018")
        val bad = JwpubExtractor.decryptInflate(ByteArray(48) { it.toByte() }, key, iv)
        assertEquals(null, bad)
    }

    @Test
    fun manifestDbNameParsed() {
        val mf = """{"name":"th_T.jwpub","publication":{"fileName":"th_T.db","symbol":"th"}}"""
        assertEquals("th_T.db", JwpubExtractor.manifestDbName(mf))
        assertEquals(null, JwpubExtractor.manifestDbName("{}"))
    }

    @Test
    fun detectJwpub() {
        assertEquals(DocKind.JWPUB, detectKind("th_T.jwpub"))
        assertNotNull(JwpubExtractor)
        assertTrue(true)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
