package com.bettertalker.app.data.util

import com.bettertalker.app.data.db.PassageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * exp/chat-quality (2026-10-21) — a citação "w25.07 22 §§ 8-9" precisa
 * casar pelo par ESTRUTURADO (page/paragraph) do índice T4.
 *
 * Antes: a comparação era só por string da ref, e a ref do acervo guarda a
 * posição SEQUENCIAL ("w25.07 Sua resposta §605") → falso aviso
 * "citação sem fonte no acervo", mesmo com o texto do § indexado.
 */
class RefContentStructuredTest {

    private val ref = RefDetector.DetectedRef(
        raw = "w25.07 22 §§ 8-9",
        kind = RefDetector.Kind.MAGAZINE,
        pubKey = "w",
        editionKey = "w|2025|7",
        label = "A Sentinela 7/2025 • pág. 22 §§ 8-9",
        page = 22,
        paragraph = 8,
        paragraphEnd = 9,
    )

    private val body = PassageEntity(
        id = "att-w25-p606",
        attachmentId = "att-w25",
        text = "Baruque, o secretário do profeta Jeremias, tinha uma designação difícil.",
        normalized = normalizeText("Baruque, o secretário do profeta Jeremias, tinha uma designação difícil."),
        section = "Sua resposta",
        ref = "w25.07 Sua resposta §606",
        page = 22,
        paragraph = 8,
        ord = 606,
    )

    @Test
    fun citacaoComPaginaEParagrafo_resolvePeloParEstruturado() {
        val check = RefDetector.resolveContent(ref, listOf(body), corpusAvailable = true)
        assertTrue("citação deveria resolver", check.resolved)
        assertNull(check.warning)
        assertNotNull(check.snippet)
        assertTrue(check.snippet!!.contains("Baruque"))
    }

    @Test
    fun citacaoDeOutroParagrafo_naoResolve() {
        val outra = body.copy(id = "x", page = 22, paragraph = 3, ord = 100)
        val check = RefDetector.resolveContent(ref, listOf(outra), corpusAvailable = true)
        assertTrue(!check.resolved)
        assertNotNull(check.warning)
    }

    @Test
    fun paginaDeOutroArtigo_naoResolve() {
        val outra = body.copy(id = "y", page = 9, paragraph = 8, ord = 101)
        val check = RefDetector.resolveContent(ref, listOf(outra), corpusAvailable = true)
        assertTrue(!check.resolved)
    }

    @Test
    fun rangeAceitaOSegundoParagrafo() {
        val p9 = body.copy(id = "z", paragraph = 9, ord = 614)
        val check = RefDetector.resolveContent(ref, listOf(p9), corpusAvailable = true)
        assertTrue(check.resolved)
        assertEquals(null, check.warning)
    }
}
