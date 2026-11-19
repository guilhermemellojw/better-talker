package com.bettertalker.app.data.bible

import com.bettertalker.app.domain.bible.BiblePassageReader
import com.bettertalker.app.domain.bible.BiblePassageSource
import com.bettertalker.app.domain.bible.BibleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BibleReferenceLinksTest {

    private class FakeSource(
        private val preferred: BibleSource? = BibleSource("tnm", "TNM", true),
        private val texts: Map<String, String> = emptyMap(),
        private val chapters: Map<String, Map<String, String>> = emptyMap(),
    ) : BiblePassageSource {
        override suspend fun preferredSource(): BibleSource? = preferred

        override suspend fun texts(
            sourceId: String,
            canonicalRefs: List<String>,
        ): Map<String, String> = canonicalRefs.mapNotNull { ref ->
            texts[ref]?.let { ref to it }
        }.toMap()

        override suspend fun chapterTexts(
            sourceId: String,
            prefix: String,
        ): Map<String, String> {
            val needle = prefix.removeSuffix("%")
            return chapters.filterKeys { it.startsWith(needle) }
                .values
                .flatMap { it.entries }
                .associate { it.key to it.value }
        }
    }

    private fun detectorText(text: String) = BibleReferenceSpanDetector.detect(text)

    @Test
    fun detectaReferenciaSimplesComSpanExato() {
        val text = "Jesus disse: (Mat. 24:14) hoje."
        val occ = detectorText(text).single()
        assertEquals("Mat. 24:14", occ.raw)
        assertEquals(text.indexOf("Mat. 24:14"), occ.start)
        assertEquals(occ.start + "Mat. 24:14".length, occ.endExclusive)
        assertEquals("Mateus", occ.book)
        assertEquals(24, occ.chapter)
        assertEquals(listOf(14), occ.verses)
        assertFalse(occ.chapterOnly)
        assertEquals(listOf("Mt 24:14"), occ.canonicalRefs)
    }

    @Test
    fun detectaIntervaloComoVersiculosReais() {
        val occ = detectorText("(Mat. 24:14-16)").single()
        assertEquals(listOf(14, 15, 16), occ.verses)
        assertEquals(listOf("Mt 24:14", "Mt 24:15", "Mt 24:16"), occ.canonicalRefs)
    }

    @Test
    fun preservaMultiplasOcorrenciasERepeticoes() {
        val text = "Leia João 3:16. Depois Gên 3:6. Repita João 3:16."
        val occs = detectorText(text)
        assertEquals(3, occs.size)
        assertEquals(listOf("João", "Gênesis", "João"), occs.map { it.book })
        assertEquals(2, occs.count { it.raw == "João 3:16" })
        assertTrue(occs[0].start < occs[1].start)
        assertTrue(occs[1].start < occs[2].start)
    }

    @Test
    fun aceitaAbreviacoesENumeroNoLivro() {
        val occ = detectorText("(1 Cor. 15:3)").single()
        assertEquals("1 Coríntios", occ.book)
        assertEquals(15, occ.chapter)
        assertEquals(listOf(3), occ.verses)
        assertEquals(listOf("1Co 15:3"), occ.canonicalRefs)
    }

    @Test
    fun aceitaCapituloInteiro() {
        val occ = detectorText("Leia Salmo 23 com atenção.").single()
        assertEquals("Salmos", occ.book)
        assertEquals(23, occ.chapter)
        assertTrue(occ.chapterOnly)
        assertTrue(occ.verses.isEmpty())
        assertEquals(listOf("Sal 23"), occ.canonicalRefs)
    }

    @Test
    fun ignoraNumerosENaoBiblicos() {
        assertTrue(detectorText("São 2025 pessoas e w25.07 § 8.").isEmpty())
        assertTrue(detectorText("Versão 6.1.0/80 e 1 Cor 15:3").isNotEmpty())
    }

    @Test
    fun ignoraCodigoInlineBlocoDeCodigoELinkMarkdown() {
        assertTrue(detectorText("`Mat. 24:14`").isEmpty())
        assertTrue(detectorText("```\nMat. 24:14\n```").isEmpty())
        assertTrue(detectorText("[Mat. 24:14](https://jw.org)").isEmpty())
        assertTrue(detectorText("https://jw.org/Mat.24:14").isEmpty())
    }

    @Test
    fun mantemReferenciaEmNegrito() {
        val occ = detectorText("**Mat. 24:14**").single()
        assertEquals("Mat. 24:14", occ.raw)
        assertEquals(2, occ.start)
    }

    @Test
    fun resolucaoDisponivelUsaEdicaoPreferidaSemAtribuirEvidencia() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(texts = mapOf("Mt 24:14" to "Boas novas em toda a terra."))
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = detectorText("(Mat. 24:14)").single()

        val result = resolver.resolve(occ)
        assertTrue(result is BibleReferenceResolution.Available)
        val target = (result as BibleReferenceResolution.Available).target
        assertEquals("tnm", target.sourceAttachmentId)
        assertEquals("TNM", target.sourceLabel)
        assertFalse(target.partial)
        assertEquals(null, target.evidenceId)
        assertEquals(listOf("Mt 24:14"), target.canonicalRefs)
    }

    @Test
    fun resolucaoParcialMantemNavegavelComAviso() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(texts = mapOf("Mt 24:14" to "Só o primeiro versículo."))
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = detectorText("(Mat. 24:14-16)").single()

        val result = resolver.resolve(occ)
        assertTrue(result is BibleReferenceResolution.Partial)
        assertTrue((result as BibleReferenceResolution.Partial).warning.isNotBlank())
        assertTrue(result.target.partial)
    }

    @Test
    fun resolucaoAusenteNaoInventaFonte() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(texts = emptyMap())
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = detectorText("(Mat. 24:14)").single()

        val result = resolver.resolve(occ)
        assertTrue(result is BibleReferenceResolution.Unavailable)
    }

    @Test
    fun semCorpusNaoRetornaAlvo() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(
            preferred = null,
            texts = mapOf("Mt 24:14" to "Texto"),
        )
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = detectorText("(Mat. 24:14)").single()

        assertTrue(resolver.resolve(occ) is BibleReferenceResolution.Unavailable)
    }

    @Test
    fun duasEdicoesUsamAPreferidaDoLeitor() = kotlinx.coroutines.runBlocking {
        val source = object : BiblePassageSource {
            override suspend fun preferredSource(): BibleSource =
                BibleSource("tnm", "TNM", true)

            override suspend fun texts(
                sourceId: String,
                canonicalRefs: List<String>,
            ): Map<String, String> = canonicalRefs.associateWith {
                if (sourceId == "tnm") "texto TNM" else "texto alternativo"
            }

            override suspend fun chapterTexts(
                sourceId: String,
                prefix: String,
            ): Map<String, String> = emptyMap()
        }
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = detectorText("(Mat. 24:14)").single()

        val result = resolver.resolve(occ) as BibleReferenceResolution.Available
        assertEquals("tnm", result.target.sourceAttachmentId)
        assertEquals("texto TNM", reader.read("Mat. 24:14").verses.single().text)
    }

    @Test
    fun referenciaComDoisLivrosEhAmbigua() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(
            texts = mapOf(
                "Mt 4:1" to "Texto de Mateus.",
                "Jo 3:16" to "Texto de João.",
            ),
        )
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = BibleReferenceOccurrence(
            raw = "Mateus 4:1; João 3:16",
            start = 0,
            endExclusive = 22,
            book = "Mateus",
            chapter = 4,
            verses = listOf(1),
            chapterOnly = false,
            canonicalRefs = listOf("Mt 4:1", "Jo 3:16"),
        )

        val result = resolver.resolve(occ)
        assertTrue(result is BibleReferenceResolution.Ambiguous)
        assertEquals(
            listOf("Mateus", "João"),
            (result as BibleReferenceResolution.Ambiguous).candidates,
        )
    }

    @Test
    fun ocorrenciaSemCanonicoNaoEhReconhecida() = kotlinx.coroutines.runBlocking {
        val source = FakeSource()
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = BibleReferenceOccurrence(
            raw = "Mat. 24:14",
            start = 0,
            endExclusive = 10,
            book = "Mateus",
            chapter = 24,
            verses = listOf(14),
            chapterOnly = false,
            canonicalRefs = emptyList(),
        )

        assertTrue(resolver.resolve(occ) is BibleReferenceResolution.NotRecognized)
    }

    @Test
    fun leitorExpoeAttachmentDaFonteLida() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(texts = mapOf("Mt 24:14" to "Texto"))
        val passage = BiblePassageReader(source).read("Mat. 24:14")
        assertEquals("tnm", passage.sourceAttachmentId)
    }

    @Test
    fun referenciaComFormatoBiblicoMasForaDoAcervoNaoEhDisponivel() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(texts = emptyMap())
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = detectorText("(Mat. 99:999)").single()

        assertTrue(resolver.resolve(occ) is BibleReferenceResolution.Unavailable)
    }

    @Test
    fun textoAntigoSemMetadadosResolveSemEvidenciaDoTurno() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(texts = mapOf("Mt 24:14" to "Texto real."))
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        // Texto antigo: nenhum turnContext/EvidenceMeta disponível.
        val occ = detectorText("Resposta antiga (Mat. 24:14).").single()
        val result = resolver.resolve(occ) as BibleReferenceResolution.Available

        assertEquals(null, result.target.evidenceId)
        assertEquals("tnm", result.target.sourceAttachmentId)
    }

    @Test
    fun intervaloDeCapitulosCobreOSpanInteiro() {
        val text = "Leia Salmo 23-24 agora."
        val occ = detectorText(text).single()
        assertEquals("Salmo 23-24", occ.raw)
        assertEquals(text.indexOf("Salmo 23-24"), occ.start)
        assertEquals(occ.start + "Salmo 23-24".length, occ.endExclusive)
        assertEquals(listOf(23, 24), occ.chapters)
        assertEquals(listOf("Sal 23", "Sal 24"), occ.canonicalRefs)
    }

    @Test
    fun intervaloMateusCobreOSpanInteiro() {
        val occ = detectorText("(Mateus 24-25)").single()
        assertEquals("Mateus 24-25", occ.raw)
        assertEquals(listOf(24, 25), occ.chapters)
        assertEquals(listOf("Mt 24", "Mt 25"), occ.canonicalRefs)
    }

    @Test
    fun capituloIsoladoContinuaFuncionando() {
        val occ = detectorText("Salmo 23").single()
        assertEquals(listOf(23), occ.chapters)
        assertTrue(occ.chapterOnly)
    }

    @Test
    fun intervalosInvalidosNaoViramLinkParcial() {
        assertTrue(detectorText("Salmo 24-23").isEmpty())
        assertTrue(detectorText("Salmo 23-200").isEmpty())
    }

    @Test
    fun intervaloEmNegritoPreservaSpanInterno() {
        val occ = detectorText("**Salmo 23-24**").single()
        assertEquals("Salmo 23-24", occ.raw)
        assertEquals(2, occ.start)
    }

    @Test
    fun intervaloDentroDeCodigoOuUrlNaoEhDetectado() {
        assertTrue(detectorText("`Salmo 23-24`").isEmpty())
        assertTrue(detectorText("https://jw.org/Salmo.23-24").isEmpty())
    }

    @Test
    fun multiplasReferenciasComIntervaloMantemOrdem() {
        val occs = detectorText("Salmo 23-24 e Mateus 5:3")
        assertEquals(2, occs.size)
        assertEquals("Salmo 23-24", occs[0].raw)
        assertEquals("Mateus 5:3", occs[1].raw)
        assertTrue(occs[0].start < occs[1].start)
    }

    @Test
    fun intervaloComFonteParcialMantemNavegavelComAviso() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(
            chapters = mapOf(
                "Sal 23:" to mapOf("Sal 23:1" to "Primeiro capítulo."),
            ),
        )
        val reader = BiblePassageReader(source)
        val resolver = BibleReferenceResolver(reader, source)
        val occ = detectorText("Salmo 23-24").single()

        val result = resolver.resolve(occ)
        assertTrue("result=$result", result is BibleReferenceResolution.Partial)
        val partial = result as BibleReferenceResolution.Partial
        assertEquals(listOf("Sal 23", "Sal 24"), partial.target.canonicalRefs)
        assertTrue(partial.warning.isNotBlank())
    }
}
