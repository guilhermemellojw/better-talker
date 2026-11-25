package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.EvidenceCategory
import com.bettertalker.app.data.copilot.EvidenceProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRUST-P0A-2 / P5 — gate produtivo de `verifyChatText()`: deduplicação
 * tipada, ausência documental e exceções. Sem LLM, Room ou dispositivo.
 */
class ChatVerificationIntegrationTest {

    private val invented = "frase bíblica inventada longa o bastante para ser sinalizada"

    @Test
    fun multiplosAvisosSemDuplicacaoEquivalente() {
        val text = "Em João 3:16, \"$invented\" e também páginas 999."
        val corpus = listOf("Texto real do acervo sobre outro tema.")
        val out = verifyChatText(text, corpus)
        assertEquals(text, out.text)
        assertNotNull(out.notice)
        // O achado literal é coberto pelo verificador anterior — nunca repetido.
        assertEquals(0, Regex(Regex.escape("“$invented”")).findAll(out.notice!!).count())
        assertTrue(out.notice!!.contains("⚠️"))
    }

    @Test
    fun corpusVazioComPedidoBiblicoNaoEhAprovacao() {
        val text = "Em João 3:16, \"$invented\"."
        val out = verifyChatText(text, emptyList())
        assertEquals(text, out.text)
        assertNotNull(out.notice)
        assertTrue(out.notice!!.contains("sem retorno do acervo"))
    }

    @Test
    fun citacaoIncompativelComReferenciaPresenteNoCorpus() {
        val corpus = listOf("João 3:16 — texto verdadeiro recuperado do acervo.")
        val out = verifyChatText("Em João 3:16, \"$invented\".", corpus)
        assertNotNull(out.notice)
        assertTrue(out.result.metrics.hasFlags)
    }

    @Test
    fun rolePlayNaoCobreCitacaoFactualNemGeraAchadoExtra() {
        val demo = "Morador: “$invented”"
        val text = "$demo. Em João 3:16, \"$invented\"."
        val out = verifyChatText(text, listOf("João 3:16 — texto real recuperado."))
        assertNotNull(out.notice)
        // O aviso base pode citar a frase factual; o papel de demonstração não
        // pode gerar um SEGUNDO achado com a mesma ocorrência.
        assertTrue(Regex(Regex.escape(invented)).findAll(out.notice!!).count() <= 1)
        assertFalse(out.notice!!.contains("Morador"))
    }

    @Test
    fun fonteParcialEMemoriaNaoSaoEvidencia() {
        val partial = EvidenceProvenance(
            sourceId = "pub", category = EvidenceCategory.CONTENT,
            recoveredText = "recorte", resolved = true, partial = true,
        )
        val memory = EvidenceProvenance(
            sourceId = "mem", category = EvidenceCategory.WRITING_MEMORY,
            recoveredText = invented, resolved = true,
        )
        assertFalse(partial.isDocumentaryEvidence())
        assertFalse(memory.isDocumentaryEvidence())
    }
}
