package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatBiblicalSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-HOTFIX-J05/P2 — ressalvas de leitura direta, truncamento e integração.
 * Exercita o MESMO seam produtivo usado por `answerAsk()`:
 * `retrieveBibleForAsk` (antes dos retornos temáticos) + `planAskAnswer`.
 */
class BibleDirectReadP2Test {

    private val literal = "Santifica-os por meio da verdade; a tua palavra é a verdade."

    private fun tnm(): Map<String, List<String>> = mapOf("João 17:17" to listOf(literal))

    private fun block(): String = retrievedBibleBlock(retrieveBibleForAsk("Leia João 17:17", tnm()))

    @Test
    fun p2a_classificacaoConservadora() {
        assertTrue(isDirectBibleRead("Leia João 17:17"))
        assertTrue(isDirectBibleRead("O que diz João 17:17?"))
        assertFalse(isDirectBibleRead("Como aplicar João 17:17?"))
        assertFalse(isDirectBibleRead("Explique João 17:17"))
        assertFalse(isDirectBibleRead("Leia esta seção"))
    }

    @Test
    fun p2b_recuperacaoAntesDosRetornosTematicos() {
        // Recuperação acontece mesmo com zero hits temáticos e zero matéria.
        val lines = retrieveBibleForAsk("Leia João 17:17", tnm())
        assertEquals(1, lines.size)
        val plan = planAskAnswer(
            topic = "Leia João 17:17", directRead = true, bibleBlock = retrievedBibleBlock(lines),
            thematicHitCount = 0, contentHitCount = 0, missingDetail = "",
            contentLines = emptyList(), missingBases = listOf("w", "lff"), simplify = false,
        )
        assertEquals(block().trim(), plan.body)
        assertTrue(plan.body.contains("João 17:17"))
        assertFalse(plan.body.contains("Não encontrei matéria temática"))
        assertFalse(plan.body.contains("publicações-guia"))
        assertFalse(plan.postBases)
    }

    @Test
    fun p2c_leituraDiretaComApenasPublicacoesGuia() {
        val plan = planAskAnswer(
            topic = "Leia João 17:17", directRead = true, bibleBlock = block(),
            thematicHitCount = 2, contentHitCount = 0, missingDetail = "",
            contentLines = emptyList(), missingBases = listOf("w"), simplify = false,
        )
        assertEquals(block().trim(), plan.body)
        assertFalse(plan.body.contains("só tenho as publicações-guia"))
        assertFalse(plan.body.contains("Baixe no site"))
        assertFalse(plan.postBases)
    }

    @Test
    fun p2d_leituraDiretaComMateriaNaoAcrescentaTematica() {
        val materia = ChatBiblicalSupport.retrievedExcerpt("TNM", "matéria temática")!!
        val plan = planAskAnswer(
            topic = "Leia João 17:17", directRead = true, bibleBlock = block(),
            thematicHitCount = 3, contentHitCount = 2, missingDetail = "",
            contentLines = listOf(materia), missingBases = listOf("w"), simplify = false,
        )
        assertEquals(block().trim(), plan.body)
        assertFalse(plan.body.contains(materia))
        assertFalse(plan.body.contains("Quer que eu gere ideias"))
        assertFalse(plan.postBases)
    }

    @Test
    fun p2e_explicacaoPreservaCaminhoTematicoSeguro() {
        val plan = planAskAnswer(
            topic = "Explique João 17:17", directRead = false, bibleBlock = block(),
            thematicHitCount = 2, contentHitCount = 0, missingDetail = "",
            contentLines = emptyList(), missingBases = listOf("w"), simplify = false,
        )
        assertTrue(plan.body.contains("só tenho as publicações-guia"))
        assertTrue(plan.postBases)
    }

    @Test
    fun p2f_tnmAusenteContinuaFalhaSegura() {
        val lines = retrieveBibleForAsk("Leia João 17:17", emptyMap())
        assertTrue(lines.isEmpty())
        val plan = planAskAnswer(
            topic = "Leia João 17:17", directRead = true, bibleBlock = "",
            thematicHitCount = 0, contentHitCount = 0, missingDetail = "",
            contentLines = emptyList(), missingBases = listOf("nwt"), simplify = false,
        )
        assertTrue(plan.body.startsWith("Não encontrei trechos sobre"))
        assertFalse(plan.body.contains(literal))
        assertTrue(plan.postBases)
    }

    @Test
    fun p2g_truncamentoExplicitoSemAlterarLiteral() {
        val longText = "x".repeat(260)
        val lines = retrieveBibleForAsk("Leia João 17:17", mapOf("João 17:17" to listOf(longText)))
        assertEquals(1, lines.size)
        assertTrue(lines.first().truncated)
        val rendered = retrievedBibleBlock(lines)
        assertTrue(rendered.contains("[recorte em 220 caracteres]"))
        assertTrue(rendered.contains(ChatBiblicalSupport.retrievedExcerpt("TNM", "x".repeat(220))!!))
    }

    @Test
    fun p2h_referenciaParcialNaoInventaVersiculo() {
        val lines = retrieveBibleForAsk("Leia João 17:17 e João 17:18", tnm())
        assertEquals(1, lines.size)
        assertFalse(retrievedBibleBlock(lines).contains("17:18"))
    }
}
