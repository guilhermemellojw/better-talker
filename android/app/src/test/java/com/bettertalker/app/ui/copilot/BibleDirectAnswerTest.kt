package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatBiblicalSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-HOTFIX-J05/P1 — fail-first na fronteira produtiva de `answerAsk()`.
 *
 * No HEAD `8abd5fb5`, a consulta `biblePassages()` fica DEPOIS dos retornos
 * antecipados de `hits.isEmpty()` e `contentHits(hits).isEmpty()`: com TNM
 * disponível e João 17:17 indexado, um pedido puramente bíblico recebe a
 * resposta de publicações-guia/bases ausentes.
 */
class BibleDirectAnswerTest {

    private fun labeled(text: String): String =
        ChatBiblicalSupport.retrievedExcerpt("TNM", text)
            ?: error("retrievedExcerpt não produziu rótulo")

    private fun verse(reference: String = "João 17:17", text: String = "texto literal recuperado") =
        RetrievedBibleLine(reference, labeled(text))

    @Test
    fun j05a_referenciaDisponivelSemMateriaTematica() {
        val block = retrievedBibleBlock(listOf(verse()))
        val body = answerAskBody(
            topic = "Leia João 17:17",
            thematicHitCount = 0,
            contentHitCount = 0,
            bibleBlock = block,
            missingDetail = "",
            contentLines = emptyList(),
            simplify = false,
        )
        assertTrue("J05: $body", body.contains("João 17:17"))
        assertTrue(body.contains(labeled("texto literal recuperado")))
        assertFalse(body.contains("só tenho as publicações-guia"))
        assertFalse(body.contains("Não encontrei trechos sobre"))
    }

    @Test
    fun j05b_referenciaAusenteContinuaFalhaSegura() {
        val body = answerAskBody(
            topic = "Leia João 17:17",
            thematicHitCount = 0,
            contentHitCount = 0,
            bibleBlock = retrievedBibleBlock(emptyList()),
            missingDetail = "",
            contentLines = emptyList(),
            simplify = false,
        )
        assertTrue(body.startsWith("Não encontrei trechos sobre"))
        assertFalse(body.contains("João 17:17 —"))
    }

    @Test
    fun j05c_publicacoesGuiaComReferenciaNaoEscondeVersiculo() {
        val body = answerAskBody(
            topic = "Leia João 17:17",
            thematicHitCount = 2,
            contentHitCount = 0,
            bibleBlock = retrievedBibleBlock(listOf(verse())),
            missingDetail = "",
            contentLines = emptyList(),
            simplify = false,
        )
        assertTrue(body.contains("João 17:17"))
        assertTrue(body.contains(labeled("texto literal recuperado")))
        assertFalse(body.contains("Baixe no site a matéria citada no esboço"))
    }

    @Test
    fun j05d_parcialNaoInventaReferenciaAusente() {
        val block = retrievedBibleBlock(listOf(verse()))
        assertEquals(1, block.lines().count())
        assertFalse(block.contains("17:18"))
    }

    @Test
    fun j05e_pedidoTematicoComReferenciaIncluiAmbos() {
        val body = answerAskBody(
            topic = "Como aplicar João 17:17?",
            thematicHitCount = 3,
            contentHitCount = 2,
            bibleBlock = retrievedBibleBlock(listOf(verse())),
            missingDetail = "",
            contentLines = listOf(labeled("matéria um"), labeled("matéria dois")),
            simplify = false,
        )
        assertTrue(body.contains("João 17:17"))
        assertTrue(body.contains("1. ${labeled("matéria um")}"))
        assertTrue(body.contains("2. ${labeled("matéria dois")}"))
        assertTrue(body.contains("Quer que eu gere ideias com isso?"))
    }

    @Test
    fun j05f_simplicaoPreservaRotuloCitacaoEReferencia() {
        // O rótulo "trecho recuperado" é protegido byte a byte (P7/F8): o
        // marcador discursivo dentro dele NÃO pode ser removido.
        val labeledVerse = labeled("“Amor é paciente” basicamente")
        val body = answerAskBody(
            topic = "Explique João 17:17 de outra maneira",
            thematicHitCount = 3,
            contentHitCount = 1,
            bibleBlock = retrievedBibleBlock(
                listOf(RetrievedBibleLine("João 17:17", labeledVerse)),
            ),
            missingDetail = "",
            contentLines = listOf(labeled("matéria temática")),
            simplify = true,
        )
        assertTrue(body.contains(labeledVerse))
        assertTrue(body.contains("João 17:17"))
        assertTrue(body.contains("“Amor é paciente”"))
        assertFalse(body.contains('\u0000'))
    }

    @Test
    fun j05g_ambiguidadeOuRefInvalidaNaoGeraBloco() {
        assertEquals("", retrievedBibleBlock(emptyList()))
        val body = answerAskBody(
            topic = "Leia Jó 3:1",
            thematicHitCount = 0,
            contentHitCount = 0,
            bibleBlock = "",
            missingDetail = "",
            contentLines = emptyList(),
            simplify = false,
        )
        assertFalse(body.contains("João 17:17"))
        assertTrue(body.startsWith("Não encontrei trechos sobre"))
    }
}
