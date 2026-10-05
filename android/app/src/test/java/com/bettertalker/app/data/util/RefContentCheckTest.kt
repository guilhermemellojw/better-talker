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

    private fun passage(id: String, ref: String, text: String) = PassageEntity(
        id = id,
        attachmentId = "att-1",
        text = text,
        normalized = normalizeText(text),
        ref = ref
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
}
