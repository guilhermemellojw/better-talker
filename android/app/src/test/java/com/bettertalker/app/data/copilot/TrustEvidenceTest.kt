package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustEvidenceTest {

    private fun item(
        id: String = "1",
        text: String = "texto",
        category: EvidenceCategory = EvidenceCategory.CONTENT,
        resolved: Boolean = true,
        waived: Boolean = false,
        recovered: String = text,
    ): EvidenceItem = EvidenceItem(
        id = id,
        reference = "ref-$id",
        text = text,
        provenance = EvidenceProvenance(
            sourceId = "src-$id",
            category = category,
            documentUnit = "unit-$id",
            recoveredText = recovered,
            resolved = resolved,
            waived = waived,
        ),
    )

    @Test
    fun conteudoRecuperadoEVidenciaDocumental() {
        val corpus = EvidenceCorpus(listOf(item()))
        assertTrue(corpus.hasDocumentaryEvidence())
        assertEquals(listOf("texto"), corpus.documentaryTexts())
    }

    @Test
    fun hipoteseDoUsuarioNaoEntraNoCorpus() {
        val conversation = ConversationContext(userText = "Baruque perdeu prestígio social")
        // O corpus só contém itens de evidência; a pergunta não vira item.
        val corpus = EvidenceCorpus(emptyList())
        assertFalse(corpus.hasDocumentaryEvidence())
        assertEquals(emptyList<String>(), corpus.documentaryTexts())
        assertTrue(conversation.userText.contains("Baruque"))
    }

    @Test
    fun historicoNaoEvidencia() {
        val conversation = ConversationContext(history = listOf("Afirmação não comprovada"))
        val corpus = EvidenceCorpus(emptyList())
        assertFalse(corpus.hasDocumentaryEvidence())
        assertTrue(conversation.history.isNotEmpty())
    }

    @Test
    fun referenciaS34SemTextoNaoEvidencia() {
        val structural = item(
            category = EvidenceCategory.STRUCTURAL,
            text = "Leia Tiago 2:17.",
            recovered = "",
            resolved = false,
        )
        val corpus = EvidenceCorpus(listOf(structural))
        assertFalse(corpus.hasDocumentaryEvidence())
        assertEquals(emptyList<String>(), corpus.documentaryTexts())
    }

    @Test
    fun textoEstruturalSemProvenienciaNaoEvidencia() {
        val structural = item(category = EvidenceCategory.STRUCTURAL, recovered = "objetivo do discurso")
        val corpus = EvidenceCorpus(listOf(structural))
        assertFalse(structural.isDocumentaryEvidence())
        assertFalse(corpus.hasDocumentaryEvidence())
    }

    @Test
    fun fonteDispensadaNaoEvidencia() {
        val waived = item(waived = true)
        val corpus = EvidenceCorpus(listOf(waived))
        assertFalse(corpus.hasDocumentaryEvidence())
    }

    @Test
    fun fonteNaoResolvidaNaoEvidencia() {
        val unresolved = item(resolved = false, recovered = "")
        val corpus = EvidenceCorpus(listOf(unresolved))
        assertFalse(corpus.hasDocumentaryEvidence())
    }

    @Test
    fun rawTextSoReferenciaNaoEvidencia() {
        val refOnly = item(
            category = EvidenceCategory.STRUCTURAL,
            text = "Leia João 3:16.",
            recovered = "Leia João 3:16.",
            resolved = false,
        )
        assertFalse(refOnly.isDocumentaryEvidence())
    }

    @Test
    fun trainingBeThMetodoNaoDocumental() {
        val training = item(category = EvidenceCategory.TRAINING, text = "Use ilustração curta")
        val corpus = EvidenceCorpus(listOf(training))
        assertFalse(corpus.hasDocumentaryEvidence())
        assertTrue(corpus.methodItems.isNotEmpty())
        assertEquals(listOf("Use ilustração curta"), corpus.methodTexts())
    }

    @Test
    fun corpusVazio() {
        val corpus = EvidenceCorpus(emptyList())
        assertTrue(corpus.isEmpty())
        assertFalse(corpus.hasDocumentaryEvidence())
        assertEquals(emptyList<String>(), corpus.documentaryTexts())
    }

    @Test
    fun citacaoAtribuidaAoUsuarioGrounded() {
        val message = "Você disse: Baruque perdeu prestígio social."
        val quote = AttributedUserQuote("Baruque perdeu prestígio social", message)
        assertTrue(quote.isGroundedInMessage())
    }

    @Test
    fun citacaoAtribuidaAoUsuarioNaoGrounded() {
        val quote = AttributedUserQuote("afirmação inventada", "mensagem original")
        assertFalse(quote.isGroundedInMessage())
    }

    @Test
    fun memoriaNaoEvidencia() {
        val memory = WritingMemory(decisions = listOf("usar João 3:16"), agreedApproach = "abertura curta")
        val corpus = EvidenceCorpus(emptyList())
        assertFalse(corpus.hasDocumentaryEvidence())
        assertTrue(memory.decisions.isNotEmpty())
    }

    @Test
    fun fonteValidaComTextoRecuperado() {
        val source = item(text = "Texto bíblico recuperado", recovered = "Texto bíblico recuperado")
        val corpus = EvidenceCorpus(listOf(source))
        assertTrue(corpus.hasDocumentaryEvidence())
        assertEquals(1, corpus.documentaryItems.size)
    }
}
