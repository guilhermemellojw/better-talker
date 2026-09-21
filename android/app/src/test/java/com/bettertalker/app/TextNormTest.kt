package com.bettertalker.app

import com.bettertalker.app.data.util.decodeBytes
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.matchBaseSlot
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.data.util.splitWithSections
import com.bettertalker.app.data.util.stripRtf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextNormTest {

    @Test
    fun rtfUnicodeBecomesChars() {
        val out = stripRtf("{\\rtf1 Primeira Tim\\u243?teo cap\\u237?tulo}")
        assertTrue("acentos convertidos: $out", out.contains("Timóteo") && out.contains("capítulo"))
    }

    @Test
    fun rtfPictRemoved() {
        val out = stripRtf("{\\rtf1 texto {\\pict\\pngblip abcdef0123456789} fim}")
        assertTrue("pict removido: $out", !out.contains("abcdef") && out.contains("texto") && out.contains("fim"))
    }

    @Test
    fun rtfStarGroupRemoved() {
        val out = stripRtf("{\\rtf1{\\*\\generator WTS5;} corpo}")
        assertTrue("generator removido: $out", !out.contains("WTS5") && out.contains("corpo"))
    }

    @Test
    fun splitFindsLessonSection() {
        val raw = "Faça perguntas\n" +
            "De modo educado, use perguntas para deixar a pessoa curiosa e chamar atenção para os pontos principais do assunto em questão.\n" +
            "Outro parágrafo curto.\n"
        val parts = splitWithSections(raw)
        assertTrue("achou frases: $parts", parts.isNotEmpty())
        assertTrue(
            "seção da lição: $parts",
            parts.any { it.second == "Faça perguntas" }
        )
    }

    @Test
    fun splitSkipsFileMarkers() {
        val parts = splitWithSections("=== th_T_03.rtf ===\nTexto real com conteúdo suficiente aqui.")
        assertTrue(parts.none { it.first.contains("===") })
    }

    @Test
    fun matchBaseOfficialNames() {
        assertEquals("be", matchBaseSlot("be_T.pdf"))
        assertEquals("th", matchBaseSlot("th_T.rtf.zip"))
        assertEquals("th", matchBaseSlot("th_T_03.rtf"))
    }

    @Test
    fun matchBaseRejectsLookalikes() {
        assertNull(matchBaseSlot("adobe_guide.pdf"))
        assertNull(matchBaseSlot("something.pdf"))
    }

    @Test
    fun detectKindByExtension() {
        assertEquals(DocKind.PDF, detectKind("a.pdf"))
        assertEquals(DocKind.EPUB, detectKind("a.epub"))
        assertEquals(DocKind.DOCX, detectKind("a.docx"))
        assertEquals(DocKind.RTF, detectKind("a.rtf"))
        assertEquals(DocKind.ZIP, detectKind("a.zip"))
        assertEquals(DocKind.TXT, detectKind("a.txt"))
        assertEquals(DocKind.JWPUB, detectKind("a.jwpub"))
        assertEquals(DocKind.UNSUPPORTED, detectKind("a.xyz"))
    }

    @Test
    fun decodePrefersUtf8FallbackLatin1() {
        assertEquals("olá", decodeBytes("olá".toByteArray(Charsets.UTF_8)))
        assertEquals("olá", decodeBytes(byteArrayOf(0x6F, 0x6C, 0xE1.toByte())))
    }

    @Test
    fun normalizeStripsAccents() {
        assertEquals("timoteo capitulo", normalizeText("Timóteo, capítulo!"))
    }

    @Test
    fun sniffZipMagic() {
        val f = File.createTempFile("ttt", ".bin")
        f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        assertEquals(DocKind.ZIP, detectKind("noext", f))
        f.delete()
    }
}
