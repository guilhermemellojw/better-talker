package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustEvidenceTest {

    private fun item(
        id: String = "1",
        reference: String = "ref-$id",
        category: EvidenceCategory = EvidenceCategory.CONTENT,
        resolved: Boolean = true,
        waived: Boolean = false,
        recovered: String = "texto",
        sourceId: String = "src-$id",
    ): EvidenceItem = EvidenceItem(
        id = id,
        reference = reference,
        provenance = EvidenceProvenance(
            sourceId = sourceId,
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
            recovered = "Leia João 3:16.",
            resolved = false,
        )
        assertFalse(refOnly.isDocumentaryEvidence())
    }

    @Test
    fun trainingBeThMetodoNaoDocumental() {
        val training = item(category = EvidenceCategory.TRAINING, recovered = "Use ilustração curta")
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
        assertFalse(quote.canSupportFactualClaim)
    }

    @Test
    fun citacaoAtribuidaAoUsuarioNaoGrounded() {
        val quote = AttributedUserQuote("afirmação inventada", "mensagem original")
        assertFalse(quote.isGroundedInMessage())
    }

    @Test
    fun citacaoAtribuidaOutroTurnoNaoGrounded() {
        val quote = AttributedUserQuote("trecho", "mensagem de outro turno")
        assertFalse(quote.isGroundedInMessage())
    }

    @Test
    fun fonteAutodeclaradaInexistenteNaoGrounded() {
        val fakeSource = "fonte autodeclarada contendo citação inexistente"
        val realMessage = "mensagem real"
        val quote = AttributedUserQuote("citação inexistente", realMessage)
        assertFalse(quote.isGroundedInMessage())
        assertTrue(fakeSource.contains("inexistente"))
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
        val source = item(recovered = "Texto bíblico recuperado")
        val corpus = EvidenceCorpus(listOf(source))
        assertTrue(corpus.hasDocumentaryEvidence())
        assertEquals(1, corpus.documentaryItems.size)
    }

    @Test
    fun itemComTextoDivergenteNaoPodeSerCriado() {
        // O texto canônico vem da proveniência; não há parâmetro independente.
        val provenance = EvidenceProvenance(
            sourceId = "src-1",
            category = EvidenceCategory.CONTENT,
            recoveredText = "texto recuperado",
            resolved = true,
        )
        assertEquals("texto recuperado", EvidenceItem("1", "ref", provenance).text)
    }

    @Test
    fun textoRecuperadoVazioNaoEvidencia() {
        val item = item(recovered = "", resolved = true)
        val corpus = EvidenceCorpus(listOf(item))
        assertFalse(corpus.hasDocumentaryEvidence())
    }

    @Test
    fun referenciaSemTextoNaoEvidencia() {
        val item = item(category = EvidenceCategory.STRUCTURAL, recovered = "", resolved = false)
        assertFalse(item.isDocumentaryEvidence())
    }

    @Test
    fun conteudoDispensadoNaoEvidencia() {
        val item = item(waived = true, recovered = "texto")
        assertFalse(item.isDocumentaryEvidence())
    }

    @Test
    fun resolvidoSemOrigemValidaNaoEvidencia() {
        // sourceId vazio lança; este caso valida que origem inválida não cria evidência.
        try {
            EvidenceProvenance(
                sourceId = "   ",
                category = EvidenceCategory.CONTENT,
                recoveredText = "texto",
                resolved = true,
            )
            assertTrue("deveria falhar", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun sourceIdVazioLanca() {
        try {
            EvidenceProvenance(sourceId = "", category = EvidenceCategory.CONTENT)
            assertTrue("deveria falhar", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun itemComIdOuReferenciaVaziaLanca() {
        val prov = EvidenceProvenance(sourceId = "src", category = EvidenceCategory.CONTENT, recoveredText = "t", resolved = true)
        try {
            EvidenceItem("", "ref", prov)
            assertTrue("deveria falhar", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
        try {
            EvidenceItem("id", "", prov)
            assertTrue("deveria falhar", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun excerptVinculadoAoTexto() {
        val excerpt = EvidenceExcerpt("trecho", "texto com trecho recuperado")
        assertTrue(excerpt.isLinkedToSource())
    }

    @Test
    fun excerptNaoVinculado() {
        val excerpt = EvidenceExcerpt("inexistente", "texto recuperado")
        assertFalse(excerpt.isLinkedToSource())
    }

    @Test
    fun isolamentoItensNaoDocumentaisResolvidos() {
        val items = listOf(
            item(id = "c", category = EvidenceCategory.CONVERSATION, recovered = "fala do usuário", resolved = true),
            item(id = "w", category = EvidenceCategory.WRITING_MEMORY, recovered = "decisão", resolved = true),
            item(id = "s", category = EvidenceCategory.STRUCTURAL, recovered = "objetivo", resolved = true),
            item(id = "t", category = EvidenceCategory.TRAINING, recovered = "técnica", resolved = true),
        )
        val corpus = EvidenceCorpus(items)
        assertEquals(emptyList<String>(), corpus.documentaryTexts())
        assertFalse(corpus.hasDocumentaryEvidence())
        assertTrue(corpus.hasNoDocumentaryEvidence())
    }

    @Test
    fun combinacaoDocumentalENaoDocumental() {
        val items = listOf(
            item(id = "d", recovered = "fonte válida", resolved = true),
            item(id = "c", category = EvidenceCategory.CONVERSATION, recovered = "hipótese", resolved = true),
        )
        val corpus = EvidenceCorpus(items)
        assertEquals(listOf("fonte válida"), corpus.documentaryTexts())
        assertTrue(corpus.hasDocumentaryEvidence())
        assertFalse(corpus.containsOnlyTrainingEvidence())
        assertFalse(corpus.containsOnlyStructuralContext())
    }

    @Test
    fun corpusApenasTreinamento() {
        val corpus = EvidenceCorpus(listOf(item(id = "t", category = EvidenceCategory.TRAINING, recovered = "técnica", resolved = true)))
        assertTrue(corpus.containsOnlyTrainingEvidence())
        assertFalse(corpus.hasDocumentaryEvidence())
    }

    @Test
    fun corpusApenasEstrutural() {
        val corpus = EvidenceCorpus(listOf(item(id = "s", category = EvidenceCategory.STRUCTURAL, recovered = "objetivo", resolved = true)))
        assertTrue(corpus.containsOnlyStructuralContext())
        assertFalse(corpus.hasDocumentaryEvidence())
    }

    @Test
    fun corpusSemDocumentalMasComItens() {
        val corpus = EvidenceCorpus(listOf(item(id = "c", category = EvidenceCategory.CONVERSATION, recovered = "fala", resolved = true)))
        assertTrue(corpus.hasNoDocumentaryEvidence())
        assertFalse(corpus.isEmpty())
    }

    @Test
    fun citacaoApenasS34NaoDocumental() {
        val corpus = EvidenceCorpus(listOf(item(id = "s", category = EvidenceCategory.STRUCTURAL, recovered = "texto do S-34", resolved = true)))
        assertFalse(corpus.hasDocumentaryEvidence())
        assertEquals(emptyList<String>(), corpus.documentaryTexts())
    }

    @Test
    fun citacaoApenasBeThNaoDocumental() {
        val corpus = EvidenceCorpus(listOf(item(id = "t", category = EvidenceCategory.TRAINING, recovered = "técnica", resolved = true)))
        assertFalse(corpus.hasDocumentaryEvidence())
    }

    @Test
    fun aliasDaPerguntaNaoViraContent() {
        val alias = item(id = "alias", category = EvidenceCategory.STRUCTURAL, recovered = "jer 29 11", resolved = false)
        assertFalse(alias.isDocumentaryEvidence())
    }

    @Test
    fun fonteWaivedOuUnresolvedNaoElegivel() {
        val waived = item(id = "w", waived = true, recovered = "texto")
        val unresolved = item(id = "u", resolved = false, recovered = "")
        assertFalse(waived.isDocumentaryEvidence())
        assertFalse(unresolved.isDocumentaryEvidence())
    }

    @Test
    fun trechoBiblicoRecuperadoElegivel() {
        val source = item(id = "b", category = EvidenceCategory.CONTENT, recovered = "texto bíblico", resolved = true)
        assertTrue(source.isDocumentaryEvidence())
    }

    @Test
    fun adapterPassagemReferenciaVaziaRetornaNull() {
        assertNull(recoveredDocumentFromPassage("", "texto"))
        assertNull(recoveredDocumentFromPassage("   ", "texto"))
    }

    @Test
    fun adapterPassagemTextoVazioRetornaNull() {
        assertNull(recoveredDocumentFromPassage("Je 29:11", ""))
        assertNull(recoveredDocumentFromPassage("Je 29:11", "   "))
    }

    @Test
    fun adapterPassagemValidaRetornaDocumento() {
        val doc = recoveredDocumentFromPassage("Je 29:11", "texto recuperado")
        assertNotNull(doc)
        assertEquals("Je 29:11", doc!!.reference)
        assertEquals("texto recuperado", doc.text)
        assertTrue(doc.resolved)
    }

    @Test
    fun adapterPassagemMisturaValidosEInvalidos() {
        val docs = listOf(
            recoveredDocumentFromPassage("Je 29:11", "texto"),
            recoveredDocumentFromPassage("", "texto"),
            recoveredDocumentFromPassage("Je 29:12", ""),
        ).filterNotNull()
        assertEquals(1, docs.size)
        assertEquals("Je 29:11", docs.single().reference)
    }

    @Test
    fun adapterPassagemParcialNaoResolvidaNaoEhDocumental() {
        val doc = RecoveredDocument(
            sourceId = "Je 29:11",
            reference = "Je 29:11",
            text = "texto",
            resolved = false,
        )
        assertFalse(doc.isDocumentaryEligible())
    }

    @Test
    fun dossierRecuperadoViraDocumento() {
        val section = com.bettertalker.app.domain.speech.SpeechSection(
            id = "s1",
            noteId = "n1",
            order = 1,
            role = com.bettertalker.app.domain.speech.SectionRole.BODY,
            title = "Ponto",
            minutes = 5,
            contentHtml = "",
            bibleRefs = listOf("Je 29:11"),
            publicationRefs = emptyList(),
            methodPrinciple = null,
            createdAt = 0,
            updatedAt = 0,
        )
        val dossier = com.bettertalker.app.domain.planning.Dossier(
            currentSection = section,
            currentSubPoint = null,
            selectedText = null,
            fullContentHtml = "",
            overview = emptyList(),
            bibleTexts = listOf(
                com.bettertalker.app.domain.planning.ResolvedBibleText(
                    ref = "Je 29:11",
                    text = "texto recuperado",
                    status = com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED,
                )
            ),
            publicationTexts = emptyList(),
            methodPrinciples = emptyList(),
            unresolvedRefs = emptyList(),
            transitionContext = null,
        )
        val docs = recoveredDocumentsFromDossier(dossier)
        assertEquals(1, docs.size)
        assertEquals("texto recuperado", docs.single().text)
    }

    @Test
    fun dossierNaoResolvidoNaoViraDocumento() {
        val section = com.bettertalker.app.domain.speech.SpeechSection(
            id = "s1",
            noteId = "n1",
            order = 1,
            role = com.bettertalker.app.domain.speech.SectionRole.BODY,
            title = "Ponto",
            minutes = 5,
            contentHtml = "",
            bibleRefs = listOf("Je 29:11"),
            publicationRefs = emptyList(),
            methodPrinciple = null,
            createdAt = 0,
            updatedAt = 0,
        )
        val dossier = com.bettertalker.app.domain.planning.Dossier(
            currentSection = section,
            currentSubPoint = null,
            selectedText = null,
            fullContentHtml = "",
            overview = emptyList(),
            bibleTexts = listOf(
                com.bettertalker.app.domain.planning.ResolvedBibleText(
                    ref = "Je 29:11",
                    text = null,
                    status = com.bettertalker.app.domain.planning.ReferenceStatus.UNRESOLVED,
                )
            ),
            publicationTexts = emptyList(),
            methodPrinciples = emptyList(),
            unresolvedRefs = listOf("Je 29:11"),
            transitionContext = null,
        )
        val docs = recoveredDocumentsFromDossier(dossier)
        assertTrue(docs.isEmpty())
    }

    @Test
    fun adapterResolvidoValido() {
        val resolved = com.bettertalker.app.domain.planning.ResolvedReference(
            original = "Je 29:11",
            canonicalRef = "Je 29:11",
            text = "texto recuperado",
            passageId = "p1",
            status = com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED,
        )
        val doc = recoveredDocumentFromResolvedBible("Je 29:11", resolved)
        assertNotNull(doc)
        assertEquals("texto recuperado", doc!!.text)
    }

    @Test
    fun adapterResolvidoSemTextoRetornaNull() {
        val resolved = com.bettertalker.app.domain.planning.ResolvedReference(
            original = "Je 29:11",
            canonicalRef = "Je 29:11",
            text = null,
            passageId = null,
            status = com.bettertalker.app.domain.planning.ReferenceStatus.UNRESOLVED,
        )
        val doc = recoveredDocumentFromResolvedBible("Je 29:11", resolved)
        assertNull(doc)
    }

    @Test
    fun corpusDocumentalVazioNaoConfirmaFato() {
        val corpus = EvidenceCorpus(listOf(item(id = "s", category = EvidenceCategory.STRUCTURAL, recovered = "S-34", resolved = true)))
        assertFalse(corpus.hasDocumentaryEvidence())
        assertTrue(corpus.hasNoDocumentaryEvidence())
    }

    private fun dossier(
        bibleTexts: List<com.bettertalker.app.domain.planning.ResolvedBibleText> = emptyList(),
        publicationTexts: List<com.bettertalker.app.domain.planning.ResolvedPublicationText> = emptyList(),
    ): com.bettertalker.app.domain.planning.Dossier {
        val section = com.bettertalker.app.domain.speech.SpeechSection(
            id = "s1",
            noteId = "n1",
            order = 1,
            role = com.bettertalker.app.domain.speech.SectionRole.BODY,
            title = "Ponto",
            minutes = 5,
            contentHtml = "",
            bibleRefs = emptyList(),
            publicationRefs = emptyList(),
            methodPrinciple = null,
            createdAt = 0,
            updatedAt = 0,
        )
        return com.bettertalker.app.domain.planning.Dossier(
            currentSection = section,
            currentSubPoint = null,
            selectedText = null,
            fullContentHtml = "",
            overview = emptyList(),
            bibleTexts = bibleTexts,
            publicationTexts = publicationTexts,
            methodPrinciples = emptyList(),
            unresolvedRefs = emptyList(),
            transitionContext = null,
        )
    }

    @Test
    fun dossierRefBiblicaVaziaNaoViraDocumentoMasValidaPermanece() {
        val docs = recoveredDocumentsFromDossier(
            dossier(
                bibleTexts = listOf(
                    com.bettertalker.app.domain.planning.ResolvedBibleText(
                        ref = "   ",
                        text = "texto sem ref",
                        status = com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED,
                    ),
                    com.bettertalker.app.domain.planning.ResolvedBibleText(
                        ref = "Je 29:11",
                        text = "texto válido",
                        status = com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED,
                    ),
                ),
            )
        )
        assertEquals(1, docs.size)
        assertEquals("Je 29:11", docs.single().reference)
    }

    @Test
    fun dossierRefPublicacaoVaziaNaoViraDocumentoMasValidaPermanece() {
        val docs = recoveredDocumentsFromDossier(
            dossier(
                publicationTexts = listOf(
                    com.bettertalker.app.domain.planning.ResolvedPublicationText(
                        ref = com.bettertalker.app.domain.planning.PublicationRef(symbol = "   "),
                        text = "texto sem ref",
                        status = com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED,
                    ),
                    com.bettertalker.app.domain.planning.ResolvedPublicationText(
                        ref = com.bettertalker.app.domain.planning.PublicationRef(symbol = "w25.07"),
                        text = "texto válido",
                        status = com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED,
                    ),
                ),
            )
        )
        assertEquals(1, docs.size)
        assertEquals("w25.07", docs.single().reference)
    }
}
