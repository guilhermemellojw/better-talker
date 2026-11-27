package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.export.parseStructureMarkdown
import com.bettertalker.app.data.llm.GroundednessVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.bettertalker.app.data.copilot.OratoryGeneration
import com.bettertalker.app.data.copilot.OratoryContextResolver
import com.bettertalker.app.data.planning.DefaultDossierPromptBuilder
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.ResolvedBibleText
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Test

/**
 * #7e — gate de groundedness nos caminhos de geração fora do chat
 * (oratória e "insira tudo"), via a função pura compartilhada.
 */
class GenerationVerificationTest {

    private val corpus = listOf(
        "Salmo 37:29 — Os justos possuirão a terra e viverão nela para sempre.",
        "w25.07 § 2 — Jeová sustenta seus servos fiéis.",
    )

    @Test
    fun oratoria_aspasInventadas_removeFraseComAviso() {
        val generated = "A Bíblia promete: \"os mansos herdarão a terra e viverão nela para sempre\". Confie nisso."
        val r = verifyGeneratedText(
            generated,
            documentarySources = corpus.mapIndexed { index, text ->
                com.bettertalker.app.data.copilot.RecoveredDocument(
                    sourceId = "src-$index",
                    reference = "src-$index",
                    text = text,
                )
            },
        )
        assertTrue(r.hasRemovals)
        assertTrue(r.text.contains(GroundednessVerifier.REMOVAL_NOTICE))
        assertTrue(r.removed.any { it.contains("os mansos herdarão a terra") })
        assertFalse(r.text.contains("os mansos herdarão a terra"))
    }

    @Test
    fun oratoria_textoLimpo_passaIntacto() {
        val generated = "Os justos possuirão a terra e viverão nela para sempre."
        val r = verifyGeneratedText(
            generated,
            documentarySources = corpus.mapIndexed { index, text ->
                com.bettertalker.app.data.copilot.RecoveredDocument(
                    sourceId = "src-$index",
                    reference = "src-$index",
                    text = text,
                )
            },
        )
        assertFalse(r.hasRemovals)
        assertEquals(generated, r.text)
    }

    @Test
    fun corpusB_fatoExclusivoDeA_ehSinalizadoETeliminado() {
        // P6 — Cenário A→B: o corpus de B (evidências do dossiê resolvido +
        // refs autorizados do S-34) não contém "fato exclusivo de A".
        val corpusB = listOf(
            "O discurso de B termina com o exemplo do Apóstolo Paulo.",
            "Referência Y — texto autorizado do S-34.",
        )
        val generated = "Citaríamos: \"fato exclusivo de A: o campeonamento de crescimento acontece apenas em Maio de 2023\"."
        val r = verifyGeneratedText(
            text = generated,
            turnContext = null,
            documentarySources = corpusB.mapIndexed { i, t ->
                com.bettertalker.app.data.copilot.RecoveredDocument(
                    sourceId = "B-$i", reference = "B-$i", text = t,
                )
            },
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("fato exclusivo de A"))
        assertFalse(r.text.contains("campeonamento de crescimento"))
        // Nenhum trecho do corpus de B é removido.
        assertFalse(r.text.contains("Apóstolo Paulo"))
    }

    @Test
    fun corpusB_fatoLegitimo_aceitoSemSinalizacao() {
        // P6 — Cenário B: evidência do ponto B no corpus; texto sem citação é aceito.
        val corpusB = listOf("O discurso de B termina com o exemplo do Apóstolo Paulo.")
        val generated = "O discurso de B termina com o exemplo do Apóstolo Paulo."
        val r = verifyGeneratedText(
            text = generated,
            turnContext = null,
            documentarySources = corpusB.mapIndexed { i, t ->
                com.bettertalker.app.data.copilot.RecoveredDocument(
                    sourceId = "B-$i", reference = "B-$i", text = t,
                )
            },
        )
        assertFalse(r.hasRemovals)
        assertEquals(generated, r.text)
    }

    @Test
    fun insiraTudo_citacaoSemApoio_removidaComAviso() {
        val markdown = "# INTRODUÇÃO\n\n" +
            "A Bíblia diz: \"a terra permanecerá para sempre e jamais passará\".\n\n" +
            "# DESENVOLVIMENTO\n\nDesenvolva o ponto principal do discurso.\n"
        val r = verifyGeneratedText(
            markdown,
            documentarySources = corpus.mapIndexed { index, text ->
                com.bettertalker.app.data.copilot.RecoveredDocument(
                    sourceId = "src-$index",
                    reference = "src-$index",
                    text = text,
                )
            },
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("jamais passará"))
        assertTrue(r.text.contains(GroundednessVerifier.REMOVAL_NOTICE))
        val sections = parseStructureMarkdown(r.text)
        assertTrue(sections.isNotEmpty())
        assertFalse(sections.joinToString(" ") { it.contentHtml }.contains("jamais passará"))
    }

    @Test
    fun corpusVazio_bloqueiaCitacaoMasNaoExplicacao() {
        // BETA-TRUST/P3/PROBE_EMPTY: ausência de corpus não autoriza citação.
        val generated = "Ele disse: \"uma frase inventada qualquer sem apoio nenhum\"."
        val r = verifyGeneratedText(generated, extraSources = emptyList())
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("frase inventada"))
        val explanation = "Ele explicou a ideia com calma e sem atribuir texto à fonte."
        assertEquals(explanation, verifyGeneratedText(explanation, extraSources = emptyList()).text)
    }

    private fun document(
        id: String,
        text: String,
        resolved: Boolean = true,
        waived: Boolean = false,
        partial: Boolean = false,
    ) = com.bettertalker.app.data.copilot.RecoveredDocument(
        sourceId = id,
        reference = id,
        text = text,
        resolved = resolved,
        waived = waived,
        partial = partial,
    )

    @Test
    fun documentoNaoResolvidoNaoAutorizaCitacao() {
        val generated = "A fonte diz: \"texto exclusivo do documento não resolvido\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(
                document("valid", "conteúdo válido diferente"),
                document("unresolved", "texto exclusivo do documento não resolvido", resolved = false),
            ),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("texto exclusivo do documento não resolvido"))
    }

    @Test
    fun documentoWaivedNaoAutorizaCitacao() {
        val generated = "A fonte diz: \"texto exclusivo do documento dispensado\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(
                document("valid", "conteúdo válido diferente"),
                document("waived", "texto exclusivo do documento dispensado", waived = true),
            ),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("texto exclusivo do documento dispensado"))
    }

    @Test
    fun structuralExtrasNaoAutorizamCitacao() {
        val generated = "O esboço diz: \"texto exclusivo do contexto estrutural\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(document("valid", "conteúdo válido diferente")),
            structuralExtras = listOf("texto exclusivo do contexto estrutural"),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("texto exclusivo do contexto estrutural"))
    }

    @Test
    fun documentoValidoAutorizaCitacaoCorrespondente() {
        val generated = "A fonte diz: \"texto exclusivo do documento válido recuperado\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(document("valid", "texto exclusivo do documento válido recuperado")),
        )
        assertFalse(r.hasRemovals)
    }

    @Test
    fun s34ETextoColadoNaoSaoProvaFactual() {
        val generated = "O texto colado diz: \"trecho do esboço colado sem origem documental\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(document("valid", "conteúdo válido diferente")),
            structuralExtras = listOf(
                "trecho do esboço colado sem origem documental",
                "S-34: objetivo e referências",
            ),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("trecho do esboço colado sem origem documental"))
    }

    // ---------- #7e-fix: aviso separado do conteúdo ----------

    @Test
    fun stripRemovalNotice_terminaComAviso_separa() {
        val text = "Conteúdo do discurso." + GroundednessVerifier.REMOVAL_NOTICE
        val (clean, notice) = stripRemovalNotice(text)
        assertEquals("Conteúdo do discurso.", clean)
        assertEquals(GroundednessVerifier.REMOVAL_NOTICE.trim(), notice)
        assertFalse(clean.contains("Observação"))
    }

    @Test
    fun stripRemovalNotice_semAviso_devolveNull() {
        val text = "Conteúdo limpo do discurso."
        val (clean, notice) = stripRemovalNotice(text)
        assertEquals(text, clean)
        assertNull(notice)
    }

    @Test
    fun stripRemovalNotice_noMeio_removeDevolveAviso() {
        val text = "Antes. " + GroundednessVerifier.REMOVAL_NOTICE.trim() + " Depois."
        val (clean, notice) = stripRemovalNotice(text)
        assertFalse(clean.contains("Observação"))
        assertEquals(GroundednessVerifier.REMOVAL_NOTICE.trim(), notice)
    }

    // ---------- P7 — adversarial: corpus factual = fontes tipadas/recuperadas ----------

    private fun dirP7(
        bibleTexts: List<ResolvedBibleText> = emptyList(),
        selectedText: String? = null,
        speakerNote: String? = null,
        methodPrinciples: List<String> = emptyList(),
        fullContentHtml: String = "",
    ) = Dossier(
        currentSection = SpeechSection(
            id = "sec-1", noteId = "n1", order = 0, role = SectionRole.BODY,
            title = "Ponto", minutes = 5, contentHtml = "", bibleRefs = emptyList(),
            publicationRefs = emptyList(), methodPrinciple = null,
            createdAt = 10L, updatedAt = 10L,
        ),
        currentSubPoint = null,
        selectedText = selectedText,
        fullContentHtml = fullContentHtml,
        overview = emptyList(),
        bibleTexts = bibleTexts,
        publicationTexts = emptyList(),
        methodPrinciples = methodPrinciples,
        unresolvedRefs = emptyList(),
        transitionContext = null,
        speakerNote = speakerNote,
    )

    private fun specP7(refs: List<OratoryGeneration.RefWithText> = emptyList()) = OratoryGeneration.Spec(
        mode = OratoryGeneration.Mode.DEVELOPMENT,
        action = OratoryGeneration.Action.INSERT,
        outlineId = "doc-1",
        outlineTitle = "DISCURSO",
        objective = null,
        orderedSections = listOf(OratoryGeneration.SectionLine("sec-1", 1, "PONTO", true)),
        current = OratoryGeneration.CurrentSection(
            sectionId = "sec-1", order = 1, title = "PONTO",
            content = "Corpo do ponto.", subsections = emptyList(),
            references = refs,
        ),
        previousSectionId = null,
        nextSectionId = null,
        training = emptyList(),
        contentSources = emptyList(),
        target = OratoryGeneration.Target.AfterSection("sec-1"),
    )

    private fun refP7(label: String, text: String?) = OratoryGeneration.RefWithText(
        type = S34RefType.BIBLE, label = label, ownerId = "sec-1", text = text
    )

    @Test
    fun focusA_naoValidaAfirmacaoDeB() {
        // A→B: afirmação X recuperada do dossiê do ponto A não valida a
        // afirmação do ponto B — evidência exclusiva de A não entra no corpus.
        val xA = "\"Esta é uma verdade recuperada apenas do ponto A.\""
        val dossierA = dirP7(listOf(ResolvedBibleText("A", xA, ReferenceStatus.RESOLVED)))
        val dossierB = dirP7(listOf(ResolvedBibleText("B", "Texto recuperado do ponto B.", ReferenceStatus.RESOLVED)))
        val corpus = OratoryGeneration.oruaryDocumentarySources(dossierB, specP7(), "sec-1")
        assertFalse(corpus.any { it.text.contains("verdade recuperada apenas do ponto A") })
        val r = verifyGeneratedText(text = "Repetiríamos: $xA", turnContext = null, documentarySources = corpus)
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("verdade recuperada apenas do ponto A"))
    }

    @Test
    fun editor_textoInventadoNaoValidaCitacao() {
        // B — texto do editor: inventado na redação; nenhuma fonte recuperada
        // o contém; o texto editorial não pode validar a citação.
        val inventado = "\"João era o aluno de Jesus, que preferia a vinha da Maria.\""
        val d = dirP7(
            selectedText = "João era o aluno de Jesus, que preferia a vinha da Maria.",
            methodPrinciples = listOf("Princípio X de oratória."),
            speakerNote = "Nota do orador X.",
            bibleTexts = listOf(ResolvedBibleText("B", "Texto recuperado do ponto B.", ReferenceStatus.RESOLVED)),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7(), "sec-1")
        assertFalse(corpus.any { it.text.contains("João") })
        val r = verifyGeneratedText(text = "Concluo assim: $inventado", turnContext = null, documentarySources = corpus)
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("João"))
    }

    @Test
    fun contextoDePratica_naoEntraNoCorpusFactual() {
        // C — contexto de prática inclui X; a geração repete X como citação;
        // contexto de prática não pode servir como prova factual.
        val pratica = "O tema nasce em conversas informais da comunidade."
        val d = dirP7(bibleTexts = listOf(ResolvedBibleText("B", "Texto recuperado do ponto B.", ReferenceStatus.RESOLVED)))
        val resolution = OratoryContextResolver.resolve(
            sectionId = "sec-1",
            resolvedDossier = d,
            includeSpeakerNote = true,
            practiceContext = pratica,
            promptBuilder = DefaultDossierPromptBuilder(),
        )
        assertTrue(resolution.contextBlock!!.contains(pratica))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7(), "sec-1")
        assertFalse(corpus.any { it.text.contains(pratica) })
        val r = verifyGeneratedText(text = "Dizíamos: \"$pratica\", claro?", turnContext = null, documentarySources = corpus)
        assertTrue(r.hasRemovals)
    }

    @Test
    fun D_fonteLegitimaPassaLiteralNoCorpus() {
        // D — dossiê da seção B possui trecho documental recuperado;
        // citação literal correspondente passa sem remoção.
        val verso = "Louvem a Jeová, todos os seus anjos, fortes e poderosos, que cumprem a sua palavra, obedecendo ao que ele diz."
        val d = dirP7(bibleTexts = listOf(ResolvedBibleText("Salpo 103:20", "[Salpo 103:20] $verso", ReferenceStatus.RESOLVED)))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7(), "sec-1")
        assertTrue(corpus.any { it.text.contains(verso) })
        val r = verifyGeneratedText(text = "O Salmo 103:20 diz: \"$verso\"", turnContext = null, documentarySources = corpus)
        assertFalse(r.hasRemovals)
        assertTrue(r.text.contains(verso))
    }

    @Test
    fun E_passagemPartialNaoValidaCitacao() {
        // E — passagem parcial: não promove documento completo; citação dele
        // não é aceita por apoio integral.
        val parcial = "[Salpo 103:20] Trecho da recuperação aproximada."
        val d = dirP7(bibleTexts = listOf(
            ResolvedBibleText("Salpo 103:20", parcial, ReferenceStatus.PARTIAL),
            ResolvedBibleText("Salpo 34", "Texto recuperado do ponto B.", ReferenceStatus.RESOLVED),
        ))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7(), "sec-1")
        assertFalse(corpus.any { it.text.contains("Trecho da recuperação aproximada") })
        val r = verifyGeneratedText(text = "Citaríamos: \"Trecho da recuperação aproximada.\"", turnContext = null, documentarySources = corpus)
        assertTrue(r.hasRemovals)
    }

    @Test
    fun F_corpusVazioNaoDeclaraAfirmacoes() {
        // F — sem evidência elegível: o gate não declara afirmação verificada.
        val d = dirP7(methodPrinciples = listOf("Princípio prático X."))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7(), "sec-1")
        assertTrue(corpus.isEmpty())
        val generated = "Confirmo que o tema é fidelidade absoluta."
        val r = verifyGeneratedText(text = generated, turnContext = null, documentarySources = corpus)
        assertFalse(r.hasRemovals)
        assertEquals(generated, r.text)
    }

    // ---------- P7.1 — escopo documental por modo (regressão) ----------

    private fun secP71(id: String, refs: List<OratoryGeneration.RefWithText> = emptyList()) = OratoryGeneration.CurrentSection(
        sectionId = id, order = id.removePrefix("sec-").toInt(), title = "Ponto $id",
        content = "Conteúdo de $id.", subsections = emptyList(), references = refs,
    )

    private fun specP71(
        mode: OratoryGeneration.Mode,
        current: OratoryGeneration.CurrentSection,
        next: OratoryGeneration.CurrentSection? = null,
    ) = OratoryGeneration.Spec(
        mode = mode,
        action = OratoryGeneration.Action.INSERT,
        outlineId = "doc-1",
        outlineTitle = "DISCURSO",
        objective = null,
        orderedSections = listOf(
            OratoryGeneration.SectionLine("sec-1", 1, "Ponto sec-1", current.sectionId == "sec-1"),
            OratoryGeneration.SectionLine("sec-2", 2, "Ponto sec-2", current.sectionId == "sec-2"),
            OratoryGeneration.SectionLine("sec-3", 3, "Ponto sec-3", current.sectionId == "sec-3"),
        ),
        current = current,
        previousSectionId = null,
        nextSectionId = next?.sectionId,
        next = next,
        training = emptyList(),
        contentSources = emptyList(),
        target = OratoryGeneration.Target.AfterSection(current.sectionId),
    )

    @Test
    fun development_refDoTopicoSeguinteNaoValidaCitacao() {
        // P7.1 — DEVELOPMENT: o prompt não apresenta refs de spec.next; a
        // fonte exclusiva de B não pode validar a citação no tópico A.
        val textoB = "Texto exclusivo do topico seguinte."
        val d = dirP7(bibleTexts = listOf(ResolvedBibleText("Salpo 34", "Texto recuperado do topico atual.", ReferenceStatus.RESOLVED)))
        val spec = specP71(
            OratoryGeneration.Mode.DEVELOPMENT,
            current = secP71("sec-1"),
            next = secP71("sec-2", refs = listOf(refP7("Ref B", textoB))),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, spec, "sec-1")
        assertTrue(corpus.isNotEmpty())
        assertFalse(corpus.any { it.text.contains(textoB) })
        val r = verifyGeneratedText(text = "Citaríamos: \"$textoB\"", turnContext = null, documentarySources = corpus)
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains(textoB))
    }

    @Test
    fun transition_refsDoAtualEdoSeguinteValem() {
        // P7.1 — TRANSITION: o prompt apresenta refs de AMBOS os tópicos;
        // ambos valem no corpus.
        val textoA = "Texto exclusivo do topico atual."
        val textoB = "Texto exclusivo do topico seguinte."
        val spec = specP71(
            OratoryGeneration.Mode.TRANSITION,
            current = secP71("sec-1", refs = listOf(refP7("Ref A", textoA))),
            next = secP71("sec-2", refs = listOf(refP7("Ref B", textoB))),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(dirP7(), spec, "sec-1")
        assertTrue(corpus.any { it.text.contains(textoA) })
        assertTrue(corpus.any { it.text.contains(textoB) })
    }

    @Test
    fun introduction_excludeRefsDoTopicoSeguinte() {
        // P7.1 — INTRODUCTION: não recebe refs de outro tópico.
        val textoB = "Texto exclusivo do topico seguinte."
        val spec = specP71(
            OratoryGeneration.Mode.INTRODUCTION,
            current = secP71("sec-1"),
            next = secP71("sec-2", refs = listOf(refP7("Ref B", textoB))),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(dirP7(), spec, "sec-1")
        assertFalse(corpus.any { it.text.contains(textoB) })
    }

    @Test
    fun conclusion_excludeRefsDoTopicoSeguinte() {
        // P7.1 — CONCLUSION: não recebe refs de outro tópico.
        val textoB = "Texto exclusivo do topico seguinte."
        val spec = specP71(
            OratoryGeneration.Mode.CONCLUSION,
            current = secP71("sec-3"),
            next = secP71("sec-2", refs = listOf(refP7("Ref B", textoB))),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(dirP7(), spec, "sec-1")
        assertFalse(corpus.any { it.text.contains(textoB) })
    }

    // ---------- F1 — compatibilidade de dossiê no corpus do gate (P2-FIX) ----------

    @Test
    fun f1_dossierIncompativelComSpec_excluiCorpusDeA() {
        // F1 — dossiê de outra seção NÃO entra no corpus (seção
        // efetivamente desenvolvida = spec.current).
        val dossierA = dirP7(bibleTexts = listOf(ResolvedBibleText("Ref A", "X exclusivo do ponto A", ReferenceStatus.RESOLVED)))
        val specB = specP71(OratoryGeneration.Mode.DEVELOPMENT, current = secP71("sec-2"))
        val corpus = OratoryGeneration.oruaryDocumentarySources(dossierA, specB, "sec-2")
        assertFalse(corpus.any { it.text.contains("X exclusivo do ponto A") })
    }

    @Test
    fun f1_dossierCompativelComSpec_trazCorpusDeB() {
        val dossierB = dirP7(bibleTexts = listOf(ResolvedBibleText("Ref B", "Texto de B.", ReferenceStatus.RESOLVED)))
        val specB = specP71(OratoryGeneration.Mode.DEVELOPMENT, current = secP71("sec-1"))
        val corpus = OratoryGeneration.oruaryDocumentarySources(dossierB, specB, "sec-1")
        assertTrue(corpus.any { it.text.contains("Texto de B.") })
    }

    @Test
    fun f1_dossiersNulo_somenteFontesDoSpec() {
        val specB = specP71(OratoryGeneration.Mode.DEVELOPMENT, current = secP71("sec-1", refs = listOf(refP7("Ref B", "Texto do spec B."))))
        val corpus = OratoryGeneration.oruaryDocumentarySources(null, specB, "sec-1")
        assertTrue(corpus.any { it.text.contains("Texto do spec B.") })
    }

    @Test
    fun f4_dossierAIncompativelComSpecB_naoSustentaAfirmacaoDeB() {
        // F4 — A→B: corpus compatível com B não aceita X exclusivo de A.
        // Controle negativo: falha ao reintroduzir o defeito.
        val xA = "X exclusivo do ponto A."
        val dossierA = dirP7(bibleTexts = listOf(ResolvedBibleText("sec-A", xA, ReferenceStatus.RESOLVED)))
        val specB = specP71(
            OratoryGeneration.Mode.DEVELOPMENT,
            current = secP71("sec-2", refs = listOf(refP7("Ref B", "Texto de B."))),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(dossierA, specB, "sec-2")
        assertFalse(corpus.any { it.text.contains(xA) })
        val r = verifyGeneratedText(text = "Citaríamos: \"$xA\".", turnContext = null, documentarySources = corpus)
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains(xA))
    }

    // ---------- F3 — PARTIAL como evidência integral (P2-FIX) ----------

    @Test
    fun f3_documentoPartial_rejeitadoComoEvidenciaIntegral() {
        // F3 — PARTIAL não entra no corpus compartilhado (isDocumentaryEligible).
        val t = "Trecho recuperado, recuperação aproximada."
        val r = verifyGeneratedText(
            text = "Citaríamos: \"$t\".",
            turnContext = null,
            documentarySources = listOf(
                document("valid", "Texto de B."),
                document("parcial", t, partial = true),
            ),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains(t))
    }

    // ---------- P4-FIX — identidade documental da seção resolvida ----------

    @Test
    fun p4_introducao_dossierLegitimoEntraNoCorpusFactual() {
        // INTRO: spec.current é o primeiro ponto, mas o dossiê resolvido é
        // da introdução — a identidade documental é "intro".
        val d = dirP7(bibleTexts = listOf(ResolvedBibleText("Ref intro", "Texto da introdução.", ReferenceStatus.RESOLVED)))
            .copy(currentSection = dirP7().currentSection.copy(id = "intro"))
        val spec = specP71(OratoryGeneration.Mode.INTRODUCTION, current = secP71("sec-1"))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, spec, "intro")
        assertTrue(corpus.any { it.text.contains("Texto da introdução.") })
    }

    @Test
    fun p4_conclusao_dossierLegitimoEntraNoCorpusFactual() {
        val d = dirP7(bibleTexts = listOf(ResolvedBibleText("Ref conclusao", "Texto da conclusão.", ReferenceStatus.RESOLVED)))
            .copy(currentSection = dirP7().currentSection.copy(id = "conclusao"))
        val spec = specP71(OratoryGeneration.Mode.CONCLUSION, current = secP71("sec-3"))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, spec, "conclusao")
        assertTrue(corpus.any { it.text.contains("Texto da conclusão.") })
    }

    @Test
    fun p4_dossierAIncompativelComB_rejeitado() {
        val dossierA = dirP7(bibleTexts = listOf(ResolvedBibleText("Ref A", "Texto exclusivo de A.", ReferenceStatus.RESOLVED)))
        val specB = specP71(OratoryGeneration.Mode.DEVELOPMENT, current = secP71("sec-2"))
        val corpus = OratoryGeneration.oruaryDocumentarySources(dossierA, specB, "sec-2")
        assertFalse(corpus.any { it.text.contains("Texto exclusivo de A.") })
    }

    @Test
    fun p4_introducao_dossierIncompativel_rejeitado() {
        val d = dirP7(bibleTexts = listOf(ResolvedBibleText("Ref intro", "Texto da introdução.", ReferenceStatus.RESOLVED)))
            .copy(currentSection = dirP7().currentSection.copy(id = "intro"))
        val spec = specP71(OratoryGeneration.Mode.INTRODUCTION, current = secP71("sec-1"))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, spec, "sec-1") // id resolvido errado
        assertFalse(corpus.any { it.text.contains("Texto da introdução.") })
    }

    @Test
    fun p4_conclusao_dossierIncompativel_rejeitado() {
        val d = dirP7(bibleTexts = listOf(ResolvedBibleText("Ref conclusao", "Texto da conclusão.", ReferenceStatus.RESOLVED)))
            .copy(currentSection = dirP7().currentSection.copy(id = "conclusao"))
        val spec = specP71(OratoryGeneration.Mode.CONCLUSION, current = secP71("sec-3"))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, spec, "sec-3") // id resolvido errado
        assertFalse(corpus.any { it.text.contains("Texto da conclusão.") })
    }

    @Test
    fun p4_transition_mantemRefsAutorizadas() {
        val atual = "Texto autorizado do ponto atual."
        val seguinte = "Texto autorizado do ponto seguinte."
        val spec = specP71(
            OratoryGeneration.Mode.TRANSITION,
            current = secP71("sec-1", refs = listOf(refP7("Ref A", atual))),
            next = secP71("sec-2", refs = listOf(refP7("Ref B", seguinte))),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(null, spec, "sec-1")
        assertTrue(corpus.any { it.text.contains(atual) })
        assertTrue(corpus.any { it.text.contains(seguinte) })
    }

    @Test
    fun p4_development_naoHerdaFontesDeOutroTopico() {
        val atual = "Texto autorizado do ponto atual."
        val outro = "Texto exclusivo do outro tópico."
        val spec = specP71(
            OratoryGeneration.Mode.DEVELOPMENT,
            current = secP71("sec-1", refs = listOf(refP7("Ref A", atual))),
            next = secP71("sec-2", refs = listOf(refP7("Ref B", outro))),
        )
        val corpus = OratoryGeneration.oruaryDocumentarySources(null, spec, "sec-1")
        assertTrue(corpus.any { it.text.contains(atual) })
        assertFalse(corpus.any { it.text.contains(outro) })
    }

    @Test
    fun p4_semIdResolvido_failClosed() {
        val d = dirP7(bibleTexts = listOf(ResolvedBibleText("Ref", "Texto do dossiê.", ReferenceStatus.RESOLVED)))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7(), null)
        assertFalse(corpus.any { it.text.contains("Texto do dossiê.") })
    }
}
