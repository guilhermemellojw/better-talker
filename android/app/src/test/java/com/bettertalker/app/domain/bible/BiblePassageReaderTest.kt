package com.bettertalker.app.domain.bible

import com.bettertalker.app.data.bible.displayBibleRefsFor
import com.bettertalker.app.data.bible.expandBibleRefs
import com.bettertalker.app.data.bible.splitBibleRefsInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.2 — leitor TNM: expansão, ordem, dedupe, status e teto. Puro. */
class BiblePassageReaderTest {

    private class FakeSource(
        private val source: BibleSource? = BibleSource("nwt", "TNM", true),
        private val texts: Map<String, String> = emptyMap(),
    ) : BiblePassageSource {
        override suspend fun preferredSource(): BibleSource? = source
        override suspend fun texts(sourceId: String, canonicalRefs: List<String>) =
            texts.filterKeys { it in canonicalRefs }
    }

    private fun reader(
        texts: Map<String, String>,
        source: BibleSource? = BibleSource("nwt", "TNM", true),
    ) = BiblePassageReader(FakeSource(source, texts))

    private fun verses(vararg refs: String): Map<String, String> =
        refs.associateWith { "texto $it" }

    @Test
    fun versoUnicoResolve() = runBlocking {
        val p = reader(verses("Jo 3:16")).read("João 3:16")
        assertEquals(BiblePassageStatus.RESOLVED, p.status)
        assertEquals("João 3:16", p.displayRef)
        assertEquals(listOf("Jo 3:16"), p.verses.map { it.ref })
        assertEquals("TNM", p.sourceLabel)
        assertNull(p.warning)
        assertFalse(p.needsLibrary)
    }

    @Test
    fun faixaPreservaOrdem() = runBlocking {
        val p = reader(verses("Ez 1:16", "Ez 1:17", "Ez 1:18"))
            .read("Ezequiel 1:16-18")
        assertEquals(listOf("Ez 1:16", "Ez 1:17", "Ez 1:18"), p.verses.map { it.ref })
        assertEquals("Ezequiel 1:16-18", p.displayRef)
        assertEquals(BiblePassageStatus.RESOLVED, p.status)
    }

    @Test
    fun listaEMultiplosLivros() = runBlocking {
        val p = reader(verses("Gên 1:26", "Gên 1:31", "Jo 3:16"))
            .read("Gên 1:26,31; João 3:16")
        assertEquals(
            listOf("Gên 1:26", "Gên 1:31", "Jo 3:16"),
            p.verses.map { it.ref },
        )
        assertEquals("Gênesis 1:26, 31; João 3:16", p.displayRef)
    }

    @Test
    fun faixaEntreCapitulosAgrupa() = runBlocking {
        val refs = (1..6).map { "Sal 23:$it" } + (1..2).map { "Sal 24:$it" }
        val p = reader(verses(*refs.toTypedArray())).read("Sal 23:1-6; 24:1-2")
        assertEquals(2, p.groups.size)
        assertEquals("Salmos 23:1-6; 24:1-2", p.displayRef)
        assertEquals(BiblePassageStatus.RESOLVED, p.status)
    }

    @Test
    fun dedupeMantemPrimeiraOcorrencia() = runBlocking {
        val p = reader(verses("Jo 3:16", "Jo 3:17"))
            .read("Jo 3:16; João 3:16,17")
        assertEquals(listOf("Jo 3:16", "Jo 3:17"), p.verses.map { it.ref })
    }

    @Test
    fun livroDesconhecidoViraMissingSemBiblioteca() = runBlocking {
        val p = reader(emptyMap()).read("Xyz 1:1")
        assertEquals(BiblePassageStatus.MISSING_CORPUS, p.status)
        assertFalse(p.needsLibrary)
        assertTrue(p.warning!!.contains("não reconhecida"))
    }

    @Test
    fun semTnmViraMissingComBiblioteca() = runBlocking {
        val p = BiblePassageReader(FakeSource(source = null)).read("Jo 3:16")
        assertEquals(BiblePassageStatus.MISSING_CORPUS, p.status)
        assertTrue(p.needsLibrary)
        assertTrue(p.warning!!.contains("TNM não está no acervo"))
    }

    @Test
    fun parcialContaEncontrados() = runBlocking {
        val p = reader(verses("Ez 1:16")).read("Ezequiel 1:16-18")
        assertEquals(BiblePassageStatus.PARTIAL, p.status)
        assertEquals("1 de 3 versículos encontrados", p.foundLabel)
        assertTrue(p.warning!!.contains("1 de 3"))
    }

    @Test
    fun semTextoViraUnresolved() = runBlocking {
        val p = reader(emptyMap()).read("Jo 3:16")
        assertEquals(BiblePassageStatus.UNRESOLVED, p.status)
        assertTrue(p.warning!!.contains("Nenhum versículo"))
        assertTrue(p.verses.isEmpty())
    }

    @Test
    fun tetoDe200MostraAvisoENaoTruncaEmSilencio() = runBlocking {
        val raw = (1..5).joinToString("; ") { ch -> "Sal $ch:1-50" } + "; Sal 6:1"
        val expanded = expandBibleRefs(raw)
        assertEquals(251, expanded.size)
        val p = reader(verses(*expanded.map { it.canonical }.toTypedArray()))
            .read(raw)
        assertEquals(200, p.verses.size)
        assertTrue(p.truncated)
        assertEquals(251, p.totalVerses)
        assertTrue(p.warning!!.contains("primeiros 200 de 251"))
        assertEquals(BiblePassageStatus.PARTIAL, p.status)
    }

    @Test
    fun ttsLeSemNumerosECopiaComNumeros() = runBlocking {
        val p = reader(verses("Ez 1:16", "Ez 1:17")).read("Ez 1:16-17")
        assertEquals("texto Ez 1:16\ntexto Ez 1:17", p.speechText)
        assertTrue(p.copyText.contains("Ezequiel 1"))
        assertTrue(p.copyText.contains("16 texto Ez 1:16"))
        assertTrue(p.copyText.contains("17 texto Ez 1:17"))
    }

    @Test
    fun parserDoEditorPreservaListaComVirgula() {
        assertEquals(
            listOf("Eze 1:16-18", "Gên 1:26,31", "Sal 23:1-6", "Jo 3:16"),
            splitBibleRefsInput("Eze 1:16-18; Gên 1:26,31; Sal 23:1-6; Jo 3:16"),
        )
        assertEquals(
            listOf("Sal 1:1", "Jo 3:16"),
            splitBibleRefsInput("Sal 1:1, Jo 3:16"),
        )
    }

    @Test
    fun displayCompactaFaixas() {
        assertEquals(
            "Ezequiel 1:16-18",
            displayBibleRefsFor("Ezequiel 1:16-18"),
        )
        assertEquals(
            "Salmos 23:1-6; 24:1-2",
            displayBibleRefsFor("Sal 23:1-6; 24:1-2"),
        )
    }
}
