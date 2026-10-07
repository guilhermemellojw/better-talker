package com.bettertalker.app

import com.bettertalker.app.data.util.decodeBytes
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.isPageUrl
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
    fun matchBase_nwt_works() {
        // T5: a Bíblia é base (corpus das refs bíblicas).
        assertEquals("nwt", matchBaseSlot("nwt_T.epub"))
        assertEquals("nwt", matchBaseSlot("nwt_T.pdf"))
        assertEquals("nwt", matchBaseSlot("Tradução do Novo Mundo.epub"))
        assertEquals("nwt", matchBaseSlot("Bíblia Sagrada.epub"))
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

    @Test
    fun pageVsFileUrl() {
        assertTrue(isPageUrl("https://www.jw.org/pt/biblioteca/revistas/g201308/"))
        assertTrue(isPageUrl("https://www.jw.org/finder?wtlocale=T&pub=be&srcid=share"))
        assertTrue(!isPageUrl("https://cfp2.jw-cdn.org/a/xyz/o/th_T.pdf"))
    }

    @Test
    fun detectKindEdges() {
        // sem extensão e .bin nunca entram (antes: descarte silencioso)
        assertEquals(DocKind.UNSUPPORTED, detectKind("download.bin"))
        assertEquals(DocKind.UNSUPPORTED, detectKind("semextensao"))
        assertEquals(DocKind.PDF, detectKind("REVISTA.PDF"))
        assertEquals(DocKind.EPUB, detectKind("wp19.numerada.epub"))
    }

    @Test
    fun ensureExtensionRescuesMime() {
        val dl = com.bettertalker.app.ui.jw.DownloadHelper
        assertEquals("x.pdf", dl.ensureExtension("x", "application/pdf"))
        assertEquals("x.epub", dl.ensureExtension("x", "application/epub+zip"))
        assertEquals("x.docx", dl.ensureExtension("x", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals("a.pdf", dl.ensureExtension("a.pdf", "text/html"))
        assertEquals("semmime", dl.ensureExtension("semmime", null))
        assertEquals("video.mp4", dl.ensureExtension("video.mp4", "video/mp4"))
    }

    @Test
    fun filterUnregisteredByName() {
        val mk = { n: String ->
            com.bettertalker.app.data.repo.DownloadCandidate("content://x/$n", n, 10L)
        }
        val cands = listOf(mk("wp19.pdf"), mk("lff.pdf"))
        val out = com.bettertalker.app.data.repo.filterUnregistered(cands, setOf("wp19.pdf"))
        assertEquals(listOf("lff.pdf"), out.map { it.name })
        assertTrue(com.bettertalker.app.data.repo.filterUnregistered(cands, setOf("wp19.pdf", "lff.pdf")).isEmpty())
    }

    // ---------- T4 (refs): seções por título de JWPUB e capítulos ----------

    @Test
    fun splitWithSections_honraTituloDeJwpub() {
        val raw = """
            # Gedalias
            GEDALIAS
            Cantor levita que serviu no templo, com detalhes suficientes para passar de cento e vinte caracteres e virar parágrafo de verdade no índice local do aplicativo.
            # Gederotaim
            Outro verbete qualquer que também passa de cento e vinte caracteres para não depender do heurístico de linha curta e ficar no título correto do índice.
        """.trimIndent()
        val pairs = splitWithSections(raw)
        // O corpo do primeiro verbete pertence a "Gedalias" (não ao anterior).
        assertTrue(pairs.first().second.lowercase().contains("gedalias"))
        assertTrue(pairs.any { it.second.lowercase().contains("gederotaim") })
        assertTrue(pairs.none { it.first.contains("Outro verbete") && it.second.lowercase().contains("gedalias") })
    }

    @Test
    fun canonicalSectionLine_capituloPorExtenso() {
        assertEquals("Capítulo 1", com.bettertalker.app.data.util.canonicalSectionLine("CAPÍTULO UM"))
        assertEquals("Capítulo 2", com.bettertalker.app.data.util.canonicalSectionLine("Capítulo dois"))
        assertEquals("Capítulo 5", com.bettertalker.app.data.util.canonicalSectionLine("Capítulo 5"))
        assertEquals("Lição 3", com.bettertalker.app.data.util.canonicalSectionLine("Lição 3"))
        // Linha que não é lição/capítulo fica como veio.
        assertEquals("Texto qualquer", com.bettertalker.app.data.util.canonicalSectionLine("Texto qualquer"))
    }

    // ---------- T4b: parágrafo numerado (§N) ----------

    @Test
    fun splitWithSectionsAndParagraphs_propagaMarcadorEResetaNaSecao() {
        val raw = """
            # Gedalias
            §1 Cantor levita que serviu no templo.
            §4 Filho de Aicão, filho de Safã.
            # Gederotaim
            Texto sem número nenhum.
        """.trimIndent()
        val triples = com.bettertalker.app.data.util.splitWithSectionsAndParagraphs(raw)
        assertEquals(1, triples.first { it.first.contains("Cantor levita") }.third)
        assertEquals(4, triples.first { it.first.contains("Filho de Aicão") }.third)
        // Nova seção reseta o parágrafo corrente.
        assertNull(triples.first { it.first.contains("Texto sem número") }.third)
    }
}
