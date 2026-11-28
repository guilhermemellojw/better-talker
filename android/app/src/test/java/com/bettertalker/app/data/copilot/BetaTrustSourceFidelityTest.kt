package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.DossierFidelityCheck
import com.bettertalker.app.domain.planning.ResolvedBibleText
import com.bettertalker.app.domain.planning.ResolvedPublicationText
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.draftAcceptAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-TRUST/P1 — regressões determinísticas das evidências AI-01, AI-02,
 * AI-05, AI-06, AI-08 e AI-10 (homologação beta 82).
 *
 * Nenhuma chamada de rede/LLM, Room, ADB ou dispositivo: apenas o gate puro.
 */
class BetaTrustSourceFidelityTest {

    private val tnmJo17 = "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade."
    private val tnmJob38 = "Jó 38:31 — Podes tu atar as influências das Plêiades?"

    private fun check(
        text: String,
        corpus: List<String>,
        questionRefs: List<String> = emptyList(),
    ) = SourceFidelityGate.enforce(text, corpus, questionRefs)

    @Test
    fun ai01_referenciasSemRetornoLocalSaoRemovidas() {
        val text = "O tema mostra ordem: Jó 38:31-33, Sal 103:20-21 e 1Co 14:33,40."
        val d = check(text, emptyList())
        assertFalse("referências sem fonte não podem sobreviver: ${d.text}", d.text.contains("Jó 38"))
        assertFalse(d.text.contains("Sal 103"))
        assertFalse(d.text.contains("1Co 14"))
        assertTrue(d.findings.contains(FidelityFinding.REFERENCE_UNSUPPORTED))
        assertTrue(d.notice!!.contains("sem fonte"))
    }

    @Test
    fun ai01_referenciaComTextoRecuperadoEhPreservada() {
        val text = "O texto diz: Jó 38:31 — Podes tu atar as influências das Plêiades?"
        val d = check(text, listOf(tnmJob38))
        assertTrue(d.text.contains("Jó 38:31"))
        assertTrue(d.findings.contains(FidelityFinding.REFERENCE_SUPPORTED))
    }

    @Test
    fun ai02_quoteInventadaComContextoBiblicoEhRemovida() {
        val text = "A Bíblia ensina: “os mansos herdarão a terra e viverão nela para sempre”."
        val d = check(text, listOf("Salmo 37:29 — Os justos possuirão a terra."))
        assertFalse("citação inventada não pode chegar ao rascunho: ${d.text}", d.text.contains("mansos herdarão"))
        assertTrue(d.findings.contains(FidelityFinding.LITERAL_UNSUPPORTED))
    }

    @Test
    fun ai05_quoteLiteralConferidaComTnmImportada() {
        val quote = "Santifica-os por meio da verdade; a tua palavra é a verdade."
        val d = check("João 17:17 — “$quote”", listOf(tnmJo17))
        assertTrue("texto da TNM deve ser preservado: ${d.text}", d.text.contains(quote))
        assertTrue(d.findings.contains(FidelityFinding.LITERAL_SUPPORTED))
    }

    @Test
    fun ai05_semTextoLocalAQuoteDaPerguntaNaoEhProva() {
        val quote = "Santifica-os por meio da verdade; a tua palavra é a verdade."
        val d = check("João 17:17 — “$quote”", emptyList(), listOf("jo1717"))
        assertFalse("referência da pergunta não autoriza citação literal: ${d.text}", d.text.contains(quote))
        assertTrue(d.findings.contains(FidelityFinding.LITERAL_UNSUPPORTED))
        assertTrue(d.findings.contains(FidelityFinding.REFERENCE_FROM_QUESTION))
    }

    @Test
    fun ai06_frasePropriaSemContextoNaoEhFalsoPositivo() {
        val text = "A expressão “Adoração Pura” é apenas o nome que usei para o tema."
        val d = check(text, listOf(tnmJo17))
        assertTrue("frase própria não pode ser removida: ${d.text}", d.text.contains("Adoração Pura"))
        assertFalse(d.findings.contains(FidelityFinding.LITERAL_UNSUPPORTED))
        assertTrue(d.findings.contains(FidelityFinding.TERM_QUOTE))
    }

    @Test
    fun ai08_e_ai10_dezenasDeCitasSemFonteBloqueiamAceiteDoDraft() {
        val quotes = listOf(
            "Jó 38:31 — “Podes contar as nuvens em multidão”",
            "Sal 103:20 — “Bendizei a Jeová, anjos dele”",
            "Dn 7:10 — “Mil milhares o serviam”",
            "1Co 14:33 — “Deus não é Deus de desordem”",
        )
        val html = quotes.joinToString("") { "<p>$it</p>" }
        val dossier = Dossier(
            currentSection = com.bettertalker.app.domain.speech.SpeechSection(
                id = "s1", noteId = "n1", order = 0,
                role = com.bettertalker.app.domain.speech.SectionRole.BODY,
                title = "Título", minutes = 5, contentHtml = "",
                bibleRefs = emptyList(), publicationRefs = emptyList(),
                methodPrinciple = null, createdAt = 0, updatedAt = 0,
            ),
            currentSubPoint = null, selectedText = null, fullContentHtml = "",
            overview = emptyList(), bibleTexts = emptyList(),
            publicationTexts = emptyList(), methodPrinciples = emptyList(),
            unresolvedRefs = emptyList(), transitionContext = null,
        )
        val report = DossierFidelityCheck.check(
            textHtml = html, usedSources = emptyList(), dossier = dossier,
        )
        assertFalse("draft com citações não verificadas não pode ser aceito", draftAcceptAllowed(report))
        assertTrue(report.unsupportedLiteralQuotes.isNotEmpty())
    }

    @Test
    fun ai10_streamingECancelamentoNaoPersistemQuoteNaoVerificada() {
        val partial = "Pense assim: “Deus promete que nunca haverá guerra”, diz a Bíblia."
        val clean = SourceFidelityGate.sanitizePartialForDisplay(partial, emptyList(), emptyList())
        assertFalse("parcial cancelado não pode conter citação sem fonte: $clean", clean.contains("nunca haverá guerra"))
    }

    @Test
    fun parafraseFundamentadaNaoEhTratadaComoCitacaoLiteral() {
        val d = check(
            "Em outras palavras, a ideia é confiar, sem atribuir texto à fonte.",
            listOf(tnmJo17),
        )
        assertTrue(d.findings.contains(FidelityFinding.PARAPHRASE_ALLOWED))
        assertFalse(d.findings.contains(FidelityFinding.LITERAL_UNSUPPORTED))
    }

    @Test
    fun p1_todasAsOitoRotasUsamOMesmoContratoDeElegibilidade() {
        val unsupported = "A Bíblia diz: “Deus promete que nunca haverá guerras nem tristeza”."
        val routes = listOf(
            ContentEligibilityGate::forFinalAnswer,
            ContentEligibilityGate::forHistory,
            ContentEligibilityGate::forResponseCopy,
            ContentEligibilityGate::forCardCopy,
            ContentEligibilityGate::forCardInsert,
            ContentEligibilityGate::forTopicInsert,
            ContentEligibilityGate::forCancelledPartial,
            ContentEligibilityGate::forDraftReview,
        )
        routes.forEachIndexed { index, route ->
            val decision = route(unsupported, emptyList(), emptyList())
            assertFalse("rota $index não pode admitir afirmação sem fonte", decision.allowed)
            assertFalse("rota $index preservou texto cru: ${decision.text}",
                decision.text.contains("nunca haverá guerras"))
            assertTrue(decision.findings.contains(FidelityFinding.LITERAL_UNSUPPORTED))
        }
    }

    @Test
    fun p1_conteudoFundamentadoPermaneceUtilizavelEmTodasAsRotas() {
        val valid = "João 17:17 — “${
            "Santifica-os por meio da verdade; a tua palavra é a verdade."
        }”"
        val routes = listOf(
            ContentEligibilityGate::forFinalAnswer,
            ContentEligibilityGate::forHistory,
            ContentEligibilityGate::forResponseCopy,
            ContentEligibilityGate::forCardCopy,
            ContentEligibilityGate::forCardInsert,
            ContentEligibilityGate::forTopicInsert,
            ContentEligibilityGate::forCancelledPartial,
            ContentEligibilityGate::forDraftReview,
        )
        routes.forEach { route ->
            val decision = route(valid, listOf(tnmJo17), emptyList())
            assertTrue(decision.allowed)
            assertTrue(decision.text.contains("Santifica-os"))
            assertNull(decision.notice)
        }
    }
}
