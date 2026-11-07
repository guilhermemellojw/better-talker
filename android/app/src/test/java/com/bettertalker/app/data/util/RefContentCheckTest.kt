package com.bettertalker.app.data.util

import com.bettertalker.app.data.db.PassageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3 — verificação de conteúdo de citações (pura, sem DB). */
class RefContentCheckTest {

    private fun ref(raw: String) = RefDetector.DetectedRef(
        raw = raw,
        kind = RefDetector.Kind.MAGAZINE,
        pubKey = "w",
        editionKey = "w|2024|1",
        label = "A Sentinela N.º 1 2024"
    )

    private fun passage(
        id: String,
        ref: String,
        text: String,
        page: Int? = null,
        paragraph: Int? = null,
        ord: Int = 0,
    ) = PassageEntity(
        id = id,
        attachmentId = "att-1",
        text = text,
        normalized = normalizeText(text),
        ref = ref,
        page = page,
        paragraph = paragraph,
        ord = ord,
    )

    @Test
    fun citacaoExistenteResolveComTrecho() {
        val p = passage("p1", "w24.01 Estudo 3 §5", "Parágrafo de apoio sobre a esperança.")
        val check = RefDetector.resolveContent(ref("w24.01"), listOf(p), corpusAvailable = true)
        assertTrue(check.resolved)
        assertNull(check.warning)
        assertTrue(check.snippet!!.contains("esperança"))
    }

    @Test
    fun citacaoInventadaGeraAviso() {
        val p = passage("p1", "w24.01 Estudo 3 §5", "Parágrafo de apoio sobre a esperança.")
        val check = RefDetector.resolveContent(ref("w24.99"), listOf(p), corpusAvailable = true)
        assertFalse(check.resolved)
        assertEquals(RefDetector.CONTENT_WARNING, check.warning)
        assertNull(check.snippet)
    }

    @Test
    fun semCorpusNaoAcusaNemResolve() {
        val check = RefDetector.resolveContent(ref("w24.01"), emptyList(), corpusAvailable = false)
        assertFalse(check.resolved)
        assertNull(check.warning)
    }

    @Test
    fun bibleCanonicaPorRef() {
        val bible = RefDetector.DetectedRef(
            raw = "Gên 1:26", kind = RefDetector.Kind.BOOK,
            pubKey = "nwt", editionKey = "book|nwt", label = "Gênesis"
        )
        val p = passage("b1", "Gên 1:26", "Deus criou os humanos à sua imagem.")
        val check = RefDetector.resolveContent(bible, listOf(p), corpusAvailable = true)
        assertTrue(check.resolved)
        assertTrue(check.snippet!!.contains("criou"))
    }

    // ---------- T6: citação por artigo/lição ----------

    @Test
    fun artigoPorNomeResolveComTrecho() {
        val it = RefDetector.DetectedRef(
            raw = "it \"Gedalias\" n.° 4", kind = RefDetector.Kind.BOOK,
            pubKey = "it", editionKey = "book|it|a:gedalias|4",
            label = "Estudo Perspicaz das Escrituras — “Gedalias” §4",
            article = "Gedalias", paragraph = 4,
        )
        // Passagem-título do JWPUB ("# Gedalias") — a agulha do artigo bate.
        val title = passage("p1", "# Gedalias", "# Gedalias")
        val check = RefDetector.resolveContent(it, listOf(title), corpusAvailable = true)
        assertTrue(check.resolved)
        assertTrue(check.snippet!!.contains("Gedalias"))
    }

    @Test
    fun artigoComRefCanonicaDoIndice() {
        val it = RefDetector.DetectedRef(
            raw = "it \"Gedalias\" n.° 4", kind = RefDetector.Kind.BOOK,
            pubKey = "it", editionKey = "book|it|a:gedalias|4",
            label = "Estudo Perspicaz das Escrituras — “Gedalias” §4",
            article = "Gedalias", paragraph = 4,
        )
        val p = passage("p4", "it Gedalias §4", "Filho de Aicão, filho de Safã.")
        val check = RefDetector.resolveContent(it, listOf(p), corpusAvailable = true)
        assertTrue(check.resolved)
    }

    // ---------- T1 (refs por página): aviso específico, UNRESOLVED mantido ----------

    @Test
    fun soPagina_recebeAvisoEspecifico() {
        // "(it 813)": detectado com page, sem unidade — nunca trecho aleatório.
        // (Sem parênteses, "it 813" sequer é detectado — gap pré-existente.)
        val ref = RefDetector.detect("conforme (it 813) sobre o tema").single()
        val p = passage("p1", "it verbete", "Texto qualquer do volume.")
        val check = RefDetector.resolveContent(ref, listOf(p), corpusAvailable = true)
        assertFalse(check.resolved)
        assertNull(check.snippet)
        assertEquals(RefDetector.PAGE_ONLY_WARNING, check.warning)
    }

    @Test
    fun paginaComCapitulo_mantemAvisoGenerico() {
        // Com unidade textual, a página é detalhe: vale o aviso genérico.
        val ref = RefDetector.DetectedRef(
            raw = "lmd lição 3 pág. 52", kind = RefDetector.Kind.BOOK,
            pubKey = "lmd", editionKey = "book|lmd",
            label = "Ame as Pessoas", chapter = "lição 3", page = 52,
        )
        val check = RefDetector.resolveContent(ref, emptyList(), corpusAvailable = true)
        assertFalse(check.resolved)
        assertEquals(RefDetector.CONTENT_WARNING, check.warning)
    }

    @Test
    fun paginaComNumeracaoLocal_usaContextoDaPagina() {
        // Despertai! 08/13 pág. 8 §§ 1-2: o índice numera os §§ 16-18, mas a
        // ref usa a numeração local. A página existe e o contexto do esboço
        // localiza o trecho — não pode virar "sem fonte no acervo".
        val ref = RefDetector.DetectedRef(
            raw = "g 8/13 pág. 8 §§ 1-2",
            kind = RefDetector.Kind.MAGAZINE,
            pubKey = "g",
            editionKey = "g|2013|8",
            label = "Despertai! 08/13 • pág. 8 §§ 1-2",
            page = 8,
            paragraph = 1,
            paragraphEnd = 2,
            context = "Temos o desejo de continuar vivendo e nunca morrer. " +
                "[Leia Eclesiastes 3:11.]",
        )
        val rows = listOf(
            passage(
                "g16", "g 08/13 pág. 8 §16",
                "Milhões de pessoas responderiam com um enfático ‘sim’.",
                page = 8, paragraph = 16, ord = 0,
            ),
            passage(
                "g17a", "g 08/13 pág. 8 §17",
                "E, visto que Deus vive para sempre, ele colocou em nós o desejo de viver para sempre.",
                page = 8, paragraph = 17, ord = 1,
            ),
            passage(
                "g17b", "g 08/13 pág. 8 §17",
                "“Pôs no coração do homem o anseio pela eternidade”, diz Eclesiastes 3:11.",
                page = 8, paragraph = 17, ord = 2,
            ),
            passage(
                "g18", "g 08/13 pág. 8 §18",
                "Existem evidências físicas de que os humanos foram projetados para viver mais.",
                page = 8, paragraph = 18, ord = 3,
            ),
        )
        val check = RefDetector.resolveContent(ref, rows, corpusAvailable = true)
        assertTrue(check.resolved)
        assertTrue(check.partial)
        assertNull(check.warning)
        assertTrue(check.snippet!!.contains("desejo de viver para sempre"))
    }

    @Test
    fun citacaoEmDuasPaginas_casaParagrafoNaSegundaPagina() {
        // rr 37-38 §§ 18-19: o §19 está na página 38, não na 37. A faixa de
        // páginas precisa entrar na checagem estruturada.
        val ref = RefDetector.DetectedRef(
            raw = "rr 37-38 §§ 18-19",
            kind = RefDetector.Kind.BOOK,
            pubKey = "rr",
            editionKey = "book|rr",
            label = "Adoração Pura pág. 37-38 §§ 18-19",
            page = 37,
            pageEnd = 38,
            paragraph = 18,
            paragraphEnd = 19,
        )
        val rows = listOf(
            passage(
                "r18", "rr pág. 37 §18",
                "O que o tamanho impressionante das rodas e seus muitos olhos nos ensinam.",
                page = 37, paragraph = 18, ord = 0,
            ),
            passage(
                "r19", "rr pág. 38 §19",
                "O carro de Jeová é muito rápido e consegue mudar de direção com facilidade.",
                page = 38, paragraph = 19, ord = 1,
            ),
        )
        val check = RefDetector.resolveContent(ref, rows, corpusAvailable = true)
        assertTrue(check.resolved)
        assertNull(check.warning)
        assertTrue(check.snippet!!.contains("mudar de direção"))
    }

    @Test
    fun semCorpus_soPaginaNaoAcusa() {
        // Sem corpus pesquisável, nem o aviso específico aparece.
        val ref = RefDetector.detect("conforme (it 813) sobre o tema").single()
        val check = RefDetector.resolveContent(ref, emptyList(), corpusAvailable = false)
        assertFalse(check.resolved)
        assertNull(check.warning)
    }
}
