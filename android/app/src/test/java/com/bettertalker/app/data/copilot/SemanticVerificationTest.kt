package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVerificationTest {

    private fun item(
        id: String = "doc-1",
        reference: String = "Je 29:11",
        text: String = "texto recuperado",
        category: EvidenceCategory = EvidenceCategory.CONTENT,
        resolved: Boolean = true,
        waived: Boolean = false,
        partial: Boolean = false,
        documentUnit: String? = "v. 11",
    ): EvidenceItem = EvidenceItem(
        id = id,
        reference = reference,
        provenance = EvidenceProvenance(
            sourceId = id,
            category = category,
            documentUnit = documentUnit,
            recoveredText = text,
            resolved = resolved,
            waived = waived,
            partial = partial,
        ),
    )

    private fun unit(
        id: String = "doc-1",
        text: String = "texto recuperado",
        kind: DocumentKind = DocumentKind.BIBLE,
        documentUnit: String? = "v. 11",
        resolved: Boolean = true,
        waived: Boolean = false,
        truncated: Boolean = false,
        completeness: EvidenceCompleteness = EvidenceCompleteness.COMPLETE,
        exposedToModel: Boolean = true,
    ): EvidenceUnit = EvidenceUnit(
        item = item(
            id = id,
            text = text,
            category = kind.requiredCategory,
            resolved = if (completeness == EvidenceCompleteness.UNKNOWN) false else resolved,
            waived = waived,
            partial = completeness == EvidenceCompleteness.PARTIAL,
            documentUnit = documentUnit,
        ),
        kind = kind,
        documentUnit = documentUnit,
        truncated = truncated,
        exposedToModel = exposedToModel,
    )

    private fun claim(
        id: String = "c1",
        type: ClaimType = ClaimType.FACTUAL_BIBLICAL,
        text: String = "Jeová prometeu restaurar seu povo.",
    ): Claim = Claim(
        id = id,
        text = text,
        span = ClaimSpan(0, text.length),
        type = type,
    )

    @Test
    fun evidenciaValidaRastreavel() {
        val evidence = unit()
        assertTrue(evidence.isFactualEligible())
        assertEquals("doc-1", evidence.documentId)
        assertEquals("Je 29:11", evidence.reference)
        assertEquals("texto recuperado", evidence.text)
    }

    @Test
    fun corpusVazioNaoEhSucesso() {
        val result = SemanticVerificationResult(emptyList())
        assertEquals(SemanticState.NOT_EVALUATED, result.overallState())
        assertFalse(result.hasDocumentarySupport())
    }

    @Test
    fun fonteNaoResolvidaNaoEhElegivel() {
        assertFalse(unit(resolved = false).isFactualEligible())
    }

    @Test
    fun fonteDispensadaNaoEhElegivel() {
        assertFalse(unit(waived = true).isFactualEligible())
    }

    @Test
    fun fonteParcialmenteRecuperadaNaoEhElegivel() {
        assertFalse(unit(resolved = false, text = "").isFactualEligible())
    }

    @Test
    fun evidenciaTruncadaNaoEhElegivelParaSuporte() {
        assertFalse(unit(truncated = true).isFactualEligible())
    }

    @Test
    fun textoNaoExpostoAoModeloNaoEhElegivel() {
        assertFalse(unit(exposedToModel = false).isFactualEligible())
    }

    @Test
    fun documentoBiblicoSemUnidadeNaoEhElegivel() {
        val evidence = EvidenceUnit(
            item = item(category = EvidenceCategory.CONTENT),
            kind = DocumentKind.BIBLE,
            documentUnit = null,
            exposedToModel = true,
        )
        assertFalse(evidence.hasDocumentUnit)
        assertFalse(evidence.isFactualEligible())
    }

    @Test
    fun documentoComIdentificacaoVaziaFalha() {
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceItem(
                id = "",
                reference = "Je 29:11",
                provenance = EvidenceProvenance(
                    sourceId = "src",
                    category = EvidenceCategory.CONTENT,
                    recoveredText = "texto",
                    resolved = true,
                ),
            )
        }
    }

    @Test
    fun categoriasSaoSeparadas() {
        assertTrue(unit(kind = DocumentKind.BIBLE).isFactualEligible())
        assertTrue(unit(kind = DocumentKind.PUBLICATION).isFactualEligible())
        assertFalse(unit(kind = DocumentKind.S34).isFactualEligible())
        assertFalse(unit(kind = DocumentKind.USER_MESSAGE).isFactualEligible())
        assertFalse(unit(kind = DocumentKind.TRAINING).isFactualEligible())
        assertFalse(item(category = EvidenceCategory.WRITING_MEMORY).isDocumentaryEvidence())
    }

    @Test
    fun kindComCategoriaIncompativelFalha() {
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceUnit(
                item = item(category = EvidenceCategory.STRUCTURAL),
                kind = DocumentKind.BIBLE,
                documentUnit = "v. 11",
            )
        }
    }

    @Test
    fun afirmacaoNaoAvaliada() {
        val claim = claim()
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict.notEvaluated(claim.id),
        )
        assertEquals(SemanticState.NOT_EVALUATED, assessment.verdict.state)
        assertEquals(SemanticState.NOT_EVALUATED, SemanticVerificationResult(listOf(assessment)).overallState())
    }

    @Test
    fun notEvaluatedNaoDeclaraEvidenciaExaminada() {
        assertThrows(IllegalArgumentException::class.java) {
            ClaimVerdict(
                claimId = "c1",
                state = SemanticState.NOT_EVALUATED,
                examinedEvidenceIds = listOf("doc-1"),
            )
        }
    }

    @Test
    fun supportedComEvidenciaIdentificavel() {
        val claim = claim()
        val evidence = unit()
        val verdict = ClaimVerdict(
            claimId = claim.id,
            state = SemanticState.SUPPORTED,
            examinedEvidenceIds = listOf(evidence.documentId),
            confidence = 0.9,
        )
        val assessment = ClaimAssessment(claim = claim, verdict = verdict, evidence = listOf(evidence))
        assertTrue(assessment.evidence.single().isFactualEligible())
        assertEquals(SemanticState.SUPPORTED, SemanticVerificationResult(listOf(assessment)).overallState())
        assertTrue(SemanticVerificationResult(listOf(assessment)).hasDocumentarySupport())
    }

    @Test
    fun supportedSemEvidenciaFalha() {
        assertThrows(IllegalArgumentException::class.java) {
            ClaimVerdict(
                claimId = "c1",
                state = SemanticState.SUPPORTED,
                examinedEvidenceIds = emptyList(),
            )
        }
    }

    @Test
    fun evidenciaExaminadaInadequadaNaoAutorizaSuporte() {
        val claim = claim(type = ClaimType.FACTUAL_BIBLICAL)
        val examinedTraining = unit(
            id = "th-1",
            text = "Use uma ilustração curta.",
            kind = DocumentKind.TRAINING,
            documentUnit = null,
        )
        val eligibleNotExamined = unit(id = "bible-1")
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.SUPPORTED,
                    examinedEvidenceIds = listOf(examinedTraining.documentId),
                ),
                evidence = listOf(examinedTraining, eligibleNotExamined),
            )
        }
    }

    @Test
    fun documentoElegivelExaminadoAutorizaSuporte() {
        val claim = claim(type = ClaimType.FACTUAL_BIBLICAL)
        val evidence = unit(id = "bible-1")
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.SUPPORTED,
                examinedEvidenceIds = listOf(evidence.documentId),
            ),
            evidence = listOf(evidence),
        )
        assertTrue(assessment.examinedEvidence().single().isFactualEligible())
    }

    @Test
    fun documentoFactualExaminadoEmClaimOratoriaFalha() {
        val claim = claim(type = ClaimType.ORATORY_TECHNIQUE)
        val factual = unit(id = "bible-1")
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.SUPPORTED,
                    examinedEvidenceIds = listOf(factual.documentId),
                ),
                evidence = listOf(factual),
            )
        }
    }

    @Test
    fun treinamentoExaminadoEmClaimFactualFalha() {
        val claim = claim(type = ClaimType.FACTUAL_BIBLICAL)
        val training = unit(
            id = "th-1",
            text = "Use uma ilustração curta.",
            kind = DocumentKind.TRAINING,
            documentUnit = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.SUPPORTED,
                    examinedEvidenceIds = listOf(training.documentId),
                ),
                evidence = listOf(training),
            )
        }
    }

    @Test
    fun idsExaminadosVaziosOuDuplicadosFalham() {
        assertThrows(IllegalArgumentException::class.java) {
            ClaimVerdict(
                claimId = "c1",
                state = SemanticState.SUPPORTED,
                examinedEvidenceIds = listOf(""),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClaimVerdict(
                claimId = "c1",
                state = SemanticState.SUPPORTED,
                examinedEvidenceIds = listOf("doc-1", "doc-1"),
            )
        }
    }

    @Test
    fun idInexistenteNoVereditoFalha() {
        val claim = claim()
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.INSUFFICIENT,
                    examinedEvidenceIds = listOf("missing"),
                ),
                evidence = listOf(unit()),
            )
        }
    }

    @Test
    fun evidenciaDuplicadaFalha() {
        val claim = claim()
        val evidence = unit(id = "doc-1")
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.INSUFFICIENT,
                    examinedEvidenceIds = listOf(evidence.documentId),
                ),
                evidence = listOf(evidence, evidence),
            )
        }
    }

    @Test
    fun supportedComEvidenciaNaoFactualFalha() {
        val claim = claim()
        val s34 = unit(kind = DocumentKind.S34, text = "texto do S-34")
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.SUPPORTED,
                    examinedEvidenceIds = listOf(s34.documentId),
                ),
                evidence = listOf(s34),
            )
        }
    }

    @Test
    fun oratoriaPodeSerSupportedComTreinamento() {
        val claim = claim(type = ClaimType.ORATORY_TECHNIQUE)
        val training = unit(
            id = "th-1",
            text = "Use uma ilustração curta na introdução.",
            kind = DocumentKind.TRAINING,
            documentUnit = null,
        )
        assertTrue(training.isMethodEligible())
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.SUPPORTED,
                examinedEvidenceIds = listOf(training.documentId),
            ),
            evidence = listOf(training),
        )
        assertEquals(SemanticState.SUPPORTED, assessment.verdict.state)
    }

    @Test
    fun treinamentoNaoSustentaAfirmacaoFactual() {
        val claim = claim(type = ClaimType.FACTUAL_BIBLICAL)
        val training = unit(
            id = "th-1",
            text = "Use uma ilustração curta.",
            kind = DocumentKind.TRAINING,
            documentUnit = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.SUPPORTED,
                    examinedEvidenceIds = listOf(training.documentId),
                ),
                evidence = listOf(training),
            )
        }
    }

    @Test
    fun hipoteseDoUsuarioNaoPodeSerSupported() {
        val claim = claim(type = ClaimType.USER_HYPOTHESIS)
        val evidence = unit()
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.SUPPORTED,
                    examinedEvidenceIds = listOf(evidence.documentId),
                ),
                evidence = listOf(evidence),
            )
        }
    }

    @Test
    fun creativeSuggestionNaoPodeSerSupported() {
        val claim = claim(type = ClaimType.CREATIVE_SUGGESTION)
        val evidence = unit()
        assertThrows(IllegalArgumentException::class.java) {
            ClaimAssessment(
                claim = claim,
                verdict = ClaimVerdict(
                    claimId = claim.id,
                    state = SemanticState.SUPPORTED,
                    examinedEvidenceIds = listOf(evidence.documentId),
                ),
                evidence = listOf(evidence),
            )
        }
    }

    @Test
    fun insufficientRepresentado() {
        val claim = claim()
        val evidence = unit()
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.INSUFFICIENT,
                examinedEvidenceIds = listOf(evidence.documentId),
                reason = "trecho relacionado, mas não conclui",
            ),
            evidence = listOf(evidence),
        )
        assertEquals(SemanticState.INSUFFICIENT, SemanticVerificationResult(listOf(assessment)).overallState())
    }

    @Test
    fun contradictedRepresentado() {
        val claim = claim()
        val evidence = unit()
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.CONTRADICTED,
                examinedEvidenceIds = listOf(evidence.documentId),
            ),
            evidence = listOf(evidence),
        )
        assertEquals(SemanticState.CONTRADICTED, SemanticVerificationResult(listOf(assessment)).overallState())
    }

    @Test
    fun unverifiableRepresentado() {
        val claim = claim()
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.UNVERIFIABLE,
                reason = "fonte não recuperada",
            ),
        )
        assertEquals(SemanticState.UNVERIFIABLE, SemanticVerificationResult(listOf(assessment)).overallState())
    }

    @Test
    fun citacaoUsuarioFielNaoAutorizaFato() {
        val attributed = AttributedUserQuote(
            quote = "Baruque perdeu prestígio social",
            trustedOriginalMessage = "Você disse: Baruque perdeu prestígio social.",
        )
        val fidelity = checkUserCitation(
            citation = "Baruque perdeu prestígio social",
            attributed = attributed,
            sourceId = "user:msg-1",
        )
        assertEquals(CitationOrigin.USER_MESSAGE, fidelity.origin)
        assertEquals(CitationFidelityState.EXACT, fidelity.state)
        assertFalse(fidelity.authorizesFactualClaim)
    }

    @Test
    fun citacaoUsuarioInventadaNaoEhFiel() {
        val attributed = AttributedUserQuote(
            quote = "Baruque perdeu prestígio social",
            trustedOriginalMessage = "Você disse: Baruque perdeu prestígio social.",
        )
        val fidelity = checkUserCitation(
            citation = "Baruque foi rei da Babilônia",
            attributed = attributed,
            sourceId = "user:msg-1",
        )
        assertEquals(CitationFidelityState.NOT_FOUND, fidelity.state)
        assertFalse(fidelity.authorizesFactualClaim)
    }

    @Test
    fun citacaoS34FielNaoAutorizaFato() {
        val fidelity = checkS34Citation(
            citation = "texto do esboço",
            s34Text = "O esboço contém o texto do esboço.",
            sourceId = "s34:doc-1",
        )
        assertEquals(CitationOrigin.S34, fidelity.origin)
        assertEquals(CitationFidelityState.EXACT, fidelity.state)
        assertFalse(fidelity.authorizesFactualClaim)
    }

    @Test
    fun citacaoDocumentalNaoJuntaUnidadesDiferentes() {
        val unitA = unit(id = "a", text = "primeira metade do texto")
        val unitB = unit(id = "b", text = "segunda metade do texto")
        val citation = "primeira metade do texto segunda metade do texto"
        assertEquals(CitationFidelityState.NOT_FOUND, checkDocumentCitation(citation, unitA).state)
        assertEquals(CitationFidelityState.NOT_FOUND, checkDocumentCitation(citation, unitB).state)
    }

    @Test
    fun citacaoDocumentalNormalizada() {
        val evidence = unit(text = "Jeová  sustenta   seus servos fiéis.")
        val fidelity = checkDocumentCitation("Jeová sustenta seus servos fiéis", evidence)
        assertEquals(CitationFidelityState.NORMALIZED, fidelity.state)
        assertFalse(fidelity.authorizesFactualClaim)
    }

    @Test
    fun citacaoDocumentalTruncadaNaoConcluiAusencia() {
        val evidence = unit(text = "trecho visível", truncated = true)
        val fidelity = checkDocumentCitation("trecho não visível", evidence)
        assertEquals(CitationFidelityState.UNVERIFIABLE, fidelity.state)
        assertFalse(fidelity.authorizesFactualClaim)
    }

    @Test
    fun identidadeDocumentalConsistente() {
        val evidence = unit(id = "doc-42")
        val reference = ClaimReference(raw = "Je 29:11", documentId = evidence.documentId)
        assertEquals("doc-42", reference.documentId)
        assertEquals(evidence.documentId, reference.documentId)
    }

    @Test
    fun imutabilidadeDosResultados() {
        val claim = claim()
        val evidence = unit()
        val original = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.INSUFFICIENT,
                examinedEvidenceIds = listOf(evidence.documentId),
            ),
            evidence = listOf(evidence),
        )
        val copy = original.copy(verdict = original.verdict.copy(state = SemanticState.CONTRADICTED))
        assertEquals(SemanticState.INSUFFICIENT, original.verdict.state)
        assertNotEquals(original.verdict.state, copy.verdict.state)
    }

    @Test
    fun metodoNaoViraSuporteDocumentalComDocumentoExtraNaoExaminado() {
        val claim = claim(type = ClaimType.ORATORY_TECHNIQUE)
        val training = unit(
            id = "th-1",
            text = "Use uma ilustração curta.",
            kind = DocumentKind.TRAINING,
            documentUnit = null,
        )
        val extraFactual = unit(id = "bible-1")
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.SUPPORTED,
                examinedEvidenceIds = listOf(training.documentId),
            ),
            evidence = listOf(training, extraFactual),
        )
        val result = SemanticVerificationResult(listOf(assessment))
        assertEquals(SemanticState.SUPPORTED, result.overallState())
        assertFalse(result.hasDocumentarySupport())
    }

    @Test
    fun suporteDocumentalExigeEvidenciaExaminada() {
        val claim = claim(type = ClaimType.FACTUAL_BIBLICAL)
        val evidence = unit(id = "bible-1")
        val assessment = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.SUPPORTED,
                examinedEvidenceIds = listOf(evidence.documentId),
            ),
            evidence = listOf(evidence),
        )
        assertTrue(SemanticVerificationResult(listOf(assessment)).hasDocumentarySupport())
    }

    @Test
    fun estadoGlobalConservador() {
        val claim = claim()
        val evidence = unit()
        val supported = ClaimAssessment(
            claim = claim,
            verdict = ClaimVerdict(
                claimId = claim.id,
                state = SemanticState.SUPPORTED,
                examinedEvidenceIds = listOf(evidence.documentId),
            ),
            evidence = listOf(evidence),
        )
        val notEvaluated = ClaimAssessment(
            claim = claim(id = "c2"),
            verdict = ClaimVerdict.notEvaluated("c2"),
        )
        assertEquals(
            SemanticState.NOT_EVALUATED,
            SemanticVerificationResult(listOf(supported, notEvaluated)).overallState(),
        )
    }

    @Test
    fun recuperacaoParcialNaoEhDocumentoCompleto() {
        val resolved = com.bettertalker.app.domain.planning.ResolvedReference(
            original = "Je 29:11",
            canonicalRef = "Je 29:11",
            text = "texto parcial",
            passageId = "p1",
            status = com.bettertalker.app.domain.planning.ReferenceStatus.PARTIAL,
        )
        val doc = recoveredDocumentFromResolvedBible("Je 29:11", resolved)
        assertTrue(doc!!.partial)
        assertFalse(doc.isDocumentaryEligible())
        val evidence = doc.asEvidenceUnit(DocumentKind.BIBLE, exposedToModel = true)
        assertEquals(EvidenceCompleteness.PARTIAL, evidence.completeness)
        assertFalse(evidence.isFactualEligible())
    }

    @Test
    fun partialNaoPodeSerPromovidoACompletePorOverride() {
        val doc = RecoveredDocument(
            sourceId = "partial-1",
            reference = "Je 29:11",
            text = "texto parcial",
            documentUnit = "v. 11",
            resolved = true,
            waived = false,
            partial = true,
        )
        val evidence = doc.asEvidenceUnit(DocumentKind.BIBLE, exposedToModel = true)
        assertEquals(EvidenceCompleteness.PARTIAL, evidence.completeness)
        assertFalse(evidence.isFactualEligible())
        // Não existe parâmetro/copy de completeness no EvidenceUnit: a
        // completude é derivada da proveniência.
        val copy = evidence.copy(exposedToModel = true)
        assertEquals(EvidenceCompleteness.PARTIAL, copy.completeness)
        assertFalse(copy.isFactualEligible())
    }

    @Test
    fun dossierComPartialPreservaIncompletude() {
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
                    text = "texto parcial",
                    status = com.bettertalker.app.domain.planning.ReferenceStatus.PARTIAL,
                )
            ),
            publicationTexts = emptyList(),
            methodPrinciples = emptyList(),
            unresolvedRefs = emptyList(),
            transitionContext = null,
        )
        val docs = recoveredDocumentsFromDossier(dossier)
        assertEquals(1, docs.size)
        assertTrue(docs.single().partial)
        assertFalse(docs.single().isDocumentaryEligible())
    }

    @Test
    fun documentoRealSemUnidadeNaoEhElegivelNemInventaUnidade() {
        val doc = RecoveredDocument(
            sourceId = "dossier:bible:Je 29:11",
            reference = "Je 29:11",
            text = "texto recuperado",
            documentUnit = null,
            resolved = true,
        )
        val evidence = doc.asEvidenceUnit(DocumentKind.BIBLE, exposedToModel = true)
        assertEquals(null, evidence.documentUnit)
        assertFalse(evidence.hasDocumentUnit)
        assertFalse(evidence.isFactualEligible())
    }

    @Test
    fun fidelidadeDocumentalExigeFonteResolvidaEDisponivel() {
        val unresolved = unit(resolved = false)
        val waived = unit(waived = true)
        val valid = unit()
        val truncated = unit(truncated = true)
        assertEquals(
            CitationFidelityState.UNVERIFIABLE,
            checkDocumentCitation("texto recuperado", unresolved).state,
        )
        assertEquals(
            CitationFidelityState.UNVERIFIABLE,
            checkDocumentCitation("texto recuperado", waived).state,
        )
        assertEquals(
            CitationFidelityState.EXACT,
            checkDocumentCitation("texto recuperado", valid).state,
        )
        assertEquals(
            CitationFidelityState.UNVERIFIABLE,
            checkDocumentCitation("texto recuperado", truncated).state,
        )
    }

    @Test
    fun recoveredDocumentAdapterPreservaIdentidade() {
        val doc = RecoveredDocument(
            sourceId = "src-1",
            reference = "Je 29:11",
            text = "texto recuperado",
            documentUnit = "v. 11",
        )
        val evidence = doc.asEvidenceUnit(DocumentKind.BIBLE, exposedToModel = true)
        assertEquals("src-1", evidence.documentId)
        assertEquals("Je 29:11", evidence.reference)
        assertEquals("texto recuperado", evidence.text)
        assertTrue(evidence.isFactualEligible())
    }
}
