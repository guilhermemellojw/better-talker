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
    fun corpusVazio_naoBloqueia() {
        val generated = "Ele disse: \"uma frase inventada qualquer sem apoio nenhum\"."
        val r = verifyGeneratedText(generated, extraSources = emptyList())
        assertFalse(r.hasRemovals)
        assertEquals(generated, r.text)
    }

    private fun document(
        id: String,
        text: String,
        resolved: Boolean = true,
        waived: Boolean = false,
    ) = com.bettertalker.app.data.copilot.RecoveredDocument(
        sourceId = id,
        reference = id,
        text = text,
        resolved = resolved,
        waived = waived,
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
        val corpus = OratoryGeneration.oruaryDocumentarySources(dossierB, specP7())
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
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7())
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
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7())
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
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7())
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
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7())
        assertFalse(corpus.any { it.text.contains("Trecho da recuperação aproximada") })
        val r = verifyGeneratedText(text = "Citaríamos: \"Trecho da recuperação aproximada.\"", turnContext = null, documentarySources = corpus)
        assertTrue(r.hasRemovals)
    }

    @Test
    fun F_corpusVazioNaoDeclaraAfirmacoes() {
        // F — sem evidência elegível: o gate não declara afirmação verificada.
        val d = dirP7(methodPrinciples = listOf("Princípio prático X."))
        val corpus = OratoryGeneration.oruaryDocumentarySources(d, specP7())
        assertTrue(corpus.isEmpty())
        val generated = "Confirmo que o tema é fidelidade absoluta."
        val r = verifyGeneratedText(text = generated, turnContext = null, documentarySources = corpus)
        assertFalse(r.hasRemovals)
        assertEquals(generated, r.text)
    }
}
