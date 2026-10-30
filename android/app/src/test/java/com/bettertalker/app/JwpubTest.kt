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

    // ---------- T4b: marcador de parágrafo numerado do JWPUB ----------

    @Test
    fun markParagraphs_numeradosDoArtigoViramMarcador() {
        val html = """
            <h1 id="p1"><strong>GEDALIAS</strong></h1>
            <p id="p2" class="sn"><strong>1.</strong> Sub título com número</p>
            <p id="p3" class="sb"><strong>1.</strong> Cantor levita.</p>
            <p id="p4" class="sb"><strong>2.</strong> Avô de Sofonias.</p>
            <p id="p5" class="si"><strong>1.</strong> Índice (não é parágrafo).</p>
        """.trimIndent()
        val out = JwpubExtractor.markParagraphs(html)
        assertTrue(out, out.contains("§1 Cantor levita"))
        assertTrue(out, out.contains("§2 Avô de Sofonias"))
        // Subtítulo (sn) e índice (si) não recebem marcador.
        assertTrue(out, !out.contains("§1 Sub título"))
        assertTrue(out, !out.contains("§1 Índice"))
    }

    @Test
    fun markParagraphs_semNumeracaoNaoMudaOTexto() {
        val html = "<p class=\"sb\">Filho de Jaque e escritor do capítulo 30.</p>"
        val out = JwpubExtractor.markParagraphs(html)
        assertTrue(out, out.contains("Filho de Jaque"))
        assertTrue(out, !out.contains("§"))
    }

    @Test
    fun markParagraphs_preservaPaginaImpressaDoHtml() {
        val html = """<p class="contextTtl"><span id="page6" class="pageNum" data-no="6"></span><strong>MATÉRIA DE CAPA</strong></p><p class="sb">Texto da página seis.</p>"""
        val out = JwpubExtractor.markParagraphs(html)
        assertTrue(out, out.contains("=== PAGE: 6 ==="))
        assertTrue(out, out.contains("Texto da página seis."))
    }

    @Test
    fun markParagraphs_naoQuebraPalavraEntreSpansInline() {
        val html = "<p class=\"sb\"><span>Jeov</span><span>á</span> Deus dá vida.</p>"
        val out = JwpubExtractor.markParagraphs(html)
        assertTrue(out, out.contains("Jeová Deus dá vida."))
    }

    // ---------- RC1-pages: página estimada dos §§ ----------

    @Test
    fun withPageMarkers_injetaMarcadorAntesDosParagrafos() {
        val marked = "§7 Texto sete.\n§8 Texto oito."
        val out = JwpubExtractor.withPageMarkers(marked, mapOf(7 to 21, 8 to 22))
        assertTrue(out.contains("=== PAGE: 21 ===\n§7 Texto sete."))
        assertTrue(out.contains("=== PAGE: 22 ===\n§8 Texto oito."))
    }

    @Test
    fun withPageMarkers_semMapa_naoMudaOTexto() {
        val marked = "§7 Texto sete."
        assertEquals(marked, JwpubExtractor.withPageMarkers(marked, emptyMap()))
    }

    @Test
    fun paragraphPages_matematicaRealDoContentamento() {
        // Dados reais do w_T_202507: artigo nas págs 20-25, 30.725 chars;
        // §8 começa em 12.479 → 20 + (12479/30725)*6 = 22.4 → pág. 22.
        val pages = JwpubExtractor.paragraphPages(
            labeledPositions = listOf(8 to 12479, 9 to 13822),
            firstPage = 20,
            lastPage = 25,
            contentLength = 30725,
        )
        assertEquals(22, pages[8])
        assertEquals(22, pages[9])
    }

    @Test
    fun paragraphPages_semDados_retornaVazio() {
        assertTrue(
            JwpubExtractor.paragraphPages(listOf(8 to 100), 0, 0, 0).isEmpty(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
