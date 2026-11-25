package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRUST-P0A-2 / BUILD P1 — proteção factual mínima (S1, S2, S4).
 *
 * Testes FAIL-FIRST: descrevem o contrato antes da implementação. Todos os
 * dados são sintéticos; nenhuma rota LLM, ADB ou persistência é exercitada.
 */
class FactualSupportProtectionTest {

    private val quote = "frase inventada que nao consta do texto realmente recuperado"

    @Test
    fun s1_referenciaCorretaCitacaoIncompativel() {
        // A ref está no corpus, mas a citação atribuída a ela não está.
        val corpus = listOf("Mateus 27:26 — texto verdadeiro recuperado do acervo local.")
        val answer = "Em Mateus 27:26, a Bíblia diz que \"$quote\"."
        assertTrue(ChatBiblicalSupport.biblicalGaps(answer, corpus).isEmpty())
        assertTrue(ChatBiblicalSupport.unsupportedQuotes(answer, corpus).contains(quote))
        val notice = ChatBiblicalSupport.noticeWithBiblicalSafety(null, answer, corpus)
        assertNotNull(notice)
        assertTrue(notice!!.contains("sem retorno do acervo local"))
    }

    @Test
    fun s2_corpusFactualVazioComCitacaoSemReferencia() {
        val answer = "A Bíblia ensina que \"$quote\"."
        assertTrue(ChatBiblicalSupport.unsupportedQuotes(answer, emptyList()).contains(quote))
        val notice = ChatBiblicalSupport.noticeWithBiblicalSafety(null, answer, emptyList())
        assertNotNull(notice)
        assertTrue(notice!!.contains("sem retorno do acervo local"))
    }

    @Test
    fun s4_memoriaOuTextoDoUsuarioNaoViraEvidencia() {
        val memory = "Decisão confirmada pelo usuário: \"$quote\""
        val userText = "O usuário escreveu: \"$quote\""
        val answer = "Sobre o tema, \"$quote\"."
        // Texto/memória podem conter a frase; corpus documental não contém.
        assertTrue(memory.contains(quote) && userText.contains(quote))
        assertTrue(ChatBiblicalSupport.unsupportedQuotes(answer, emptyList()).contains(quote))

        val corpus = EvidenceCorpus(
            items = listOf(
                EvidenceItem(
                    id = "mem", reference = "decisão", provenance = EvidenceProvenance(
                        sourceId = "mem", category = EvidenceCategory.WRITING_MEMORY,
                        recoveredText = memory, resolved = true,
                    ),
                ),
                EvidenceItem(
                    id = "user", reference = "pergunta", provenance = EvidenceProvenance(
                        sourceId = "user", category = EvidenceCategory.CONVERSATION,
                        recoveredText = userText, resolved = true,
                    ),
                ),
                EvidenceItem(
                    id = "s34", reference = "ref sem texto", provenance = EvidenceProvenance(
                        sourceId = "s34", category = EvidenceCategory.STRUCTURAL,
                        recoveredText = "Romanos 8", resolved = true,
                    ),
                ),
            ),
        )
        assertTrue(corpus.documentaryTexts().isEmpty())
        assertFalse(corpus.hasDocumentaryEvidence())
    }

    @Test
    fun s2_avisoDeAusenciaNaoPodeFaltarQuandoJaExisteOutroAviso() {
        val answer = "Em Mateus 27:26, \"$quote\"."
        val existing = "⚠️ Revise: 1 número sinalizado."
        val notice = ChatBiblicalSupport.noticeWithBiblicalSafety(existing, answer, emptyList())
        assertNotNull(notice)
        assertTrue(notice!!.contains(existing))
        assertTrue(notice.contains("sem retorno do acervo local"))
    }

    @Test
    fun positivo_mentoriaExclusivamenteOratoriaSemGap() {
        val answer = "Use o BE 39-42 para montar o esboço e a TH para a aplicação."
        assertTrue(ChatBiblicalSupport.biblicalGaps(answer, emptyList()).isEmpty())
        assertTrue(ChatBiblicalSupport.unsupportedQuotes(answer, emptyList()).isEmpty())
        assertNull(ChatBiblicalSupport.noticeWithBiblicalSafety(null, answer, emptyList()))
    }

    @Test
    fun rotuloCorrespondeApenasAoTrechoRecuperado() {
        assertEquals(
            "TNM — trecho recuperado: “texto efetivamente lido do versículo”",
            ChatBiblicalSupport.retrievedExcerpt("TNM", "texto efetivamente lido do versículo"),
        )
        assertNull(ChatBiblicalSupport.retrievedExcerpt("TNM", ""))
    }

    @Test
    fun s3_parcialNaoPromoveADocumentoCompleto() {
        // Proteção existente (#48/TrustEvidence) verificada: sem escape novo, S3 não é bloqueador.
        assertFalse(
            EvidenceProvenance(
                sourceId = "pub", category = EvidenceCategory.CONTENT,
                recoveredText = "recorte", resolved = true, partial = true,
            ).isDocumentaryEvidence(),
        )
    }

    // ---------- controles adicionais do BUILD P2 ----------

    @Test
    fun citacaoEmOutraFonteNaoComprovaAReferenciaAtribuida() {
        val answer = "Em Mateus 27:26, a Bíblia diz que \"$quote\"."
        val corpus = listOf(
            "Romanos 8:1 — documento sem a frase.",
            "Outra publicação — $quote",
        )
        // A frase existe em outra fonte, mas não no documento da ref atribuída.
        assertFalse(ChatBiblicalSupport.unsupportedQuotes(answer, corpus).contains(quote))
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer, corpus).contains(quote))
        val notice = ChatBiblicalSupport.supportNotice(answer, corpus)
        assertNotNull(notice)
        assertTrue(notice!!.contains("atribuída(s) a referência sem fonte correspondente"))
    }

    @Test
    fun concatenacaoDeFontesNaoCriaCorrespondencia() {
        val left = "AlphaAlphaAlpha"
        val right = "BetaBetaBeta"
        val quote = "$left $right" // 29 caracteres — existe só no texto concatenado
        assertTrue(quote.length >= 20)
        assertTrue(ChatBiblicalSupport.unsupportedQuotes("Fonte: \"$quote\"", listOf(left, right)).contains(quote))
        assertTrue(ChatBiblicalSupport.unsupportedQuotes("Fonte: \"$quote\"", listOf("$left $right")).isEmpty())
    }

    @Test
    fun marcadorDeSugestaoNaoDispensaAfirmacaoFactual() {
        val answer = "〈sugestão〉\"$quote\"〈/sugestão〉 — como em João 3:16."
        assertTrue(ChatBiblicalSupport.unsupportedQuotes(answer, emptyList()).contains(quote))
        // Prosa criativa sem ref continua isenta.
        assertTrue(ChatBiblicalSupport.unsupportedQuotes("〈sugestão〉\"$quote\"〈/sugestão〉", emptyList()).isEmpty())
    }

    // ---------- BUILD P3 — F1 atribuição, F2 deduplicação, F3 exceções ----------

    private val q1 = "primeira frase biblica longa o suficiente para deteccao literal"
    private val q2 = "segunda frase biblica longa o suficiente para deteccao literal"

    @Test
    fun f1_duasReferenciasComCitacoesTrocadas() {
        val answer = "Em João 3:16 diz \"$q1\". Em João 3:17 diz \"$q2\"."
        val corpus = listOf("João 3:16 — $q2", "João 3:17 — $q1")
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer, corpus).contains(q1))
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer, corpus).contains(q2))
    }

    @Test
    fun f1_versiculo1NaoCertificaCitacaoDoVersiculo10() {
        val answer = "Em João 3:10 diz \"$q1\"."
        val corpus = listOf("João 3:1 — $q1")
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer, corpus).contains(q1))
    }

    @Test
    fun f1_referenciaMencionadaSemVinculoComACitacao() {
        val answer = "Sobre João 3:16 há muito a dizer. " + "Outro assunto ocupa esta mensagem. ".repeat(9) +
            "Aliás, a Bíblia diz que \"$q1\"."
        val corpus = listOf("João 3:16 — $q1")
        // Ref existe globalmente, mas não está vinculada à citação examinada.
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer, corpus).contains(q1))
    }

    @Test
    fun f2_avisoExistenteNaoSuprimeAtribuicaoIncorreta() {
        val answer = "Em João 3:16 diz \"$q1\"."
        val corpus = listOf("João 3:17 — $q1")
        val existing = "⚠️ Revise: 1 citação fora dos trechos."
        val notice = ChatBiblicalSupport.noticeWithBiblicalSafety(existing, answer, corpus)
        assertNotNull(notice)
        assertTrue(notice!!.contains(existing))
        assertTrue(notice.contains("atribuída(s) a referência sem fonte correspondente"))
    }

    @Test
    fun f3_rolePlayComReferenciaNaoViraEvasao() {
        val demo = "<p><b>Morador:</b> “$q1”</p><p><b>Publicador:</b> “$q2”</p>"
        val answer = "$demo Em João 3:16 há uma promessa."
        assertTrue(ChatBiblicalSupport.unsupportedQuotes(answer, emptyList()).isEmpty())
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer, emptyList()).isEmpty())
    }

    @Test
    fun f3_prosaCriativaEFatoExplicitoSaoDistintos() {
        val answer = "〈sugestão〉\"$q1\"〈/sugestão〉; em João 3:16 diz \"$q2\"."
        val raw = com.bettertalker.app.data.llm.GroundednessVerifier.factualQuotes(answer)
        val unsupported = ChatBiblicalSupport.unsupportedQuotes(answer, emptyList())
        assertTrue(raw.containsAll(listOf(q1, q2)))
        assertTrue(unsupported.contains(q2))
        assertTrue(unsupported.contains(q1)) // sugestão com ref bíblica não é isenção
    }

    // ---------- BUILD P4 FOCAL — F1.1 / F1.2 / F2.1 ----------

    private val qA = "trecho A suficientemente longo para deteccao literal"
    private val qB = "trecho B suficientemente longo para deteccao literal"

    @Test
    fun f1_1_atribuicaoCorretaSemAvisoIndevido() {
        val answer = "Em João 3:16 diz \"$qA\"."
        val corpus = listOf("João 3:16 — $qA")
        assertTrue(ChatBiblicalSupport.biblicalGaps(answer, corpus).isEmpty())
        assertTrue(ChatBiblicalSupport.unsupportedQuotes(answer, corpus).isEmpty())
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer, corpus).isEmpty())
        assertNull(ChatBiblicalSupport.noticeWithBiblicalSafety(null, answer, corpus))
    }

    @Test
    fun f1_1_versiculo1E10NosDoisSentidos() {
        val answer1 = "Em João 3:1 diz \"$qA\"."
        val answer10 = "Em João 3:10 diz \"$qA\"."
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer1, listOf("João 3:1 — $qA")).isEmpty())
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer10, listOf("João 3:1 — $qA")).isNotEmpty())
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer1, listOf("João 3:10 — $qA")).isNotEmpty())
        assertTrue(ChatBiblicalSupport.misattributedQuotes(answer10, listOf("João 3:10 — $qA")).isEmpty())
    }

    @Test
    fun f1_2_mesmaFraseDuasOcorrenciasComAtribuicoesDistintas() {
        val answer = "Em João 3:16 diz \"$qA\". Em João 3:17 diz \"$qA\"."
        val occurrences = ChatBiblicalSupport.misattributedQuoteOccurrences(
            answer, listOf("João 3:16 — $qA"),
        )
        assertEquals(1, occurrences.size)
        assertEquals(answer.lastIndexOf(qA), occurrences.first().start)
    }

    @Test
    fun f1_2_rolePlayPrimeiroEAfirmacaoDepois() {
        val answer = "<p><b>Morador:</b> “$qA”</p> Em João 3:17 diz \"$qA\"."
        val occurrences = ChatBiblicalSupport.misattributedQuoteOccurrences(
            answer, listOf("João 3:16 — $qA"),
        )
        assertEquals(1, occurrences.size)
        assertEquals(answer.lastIndexOf(qA), occurrences.first().start)
    }

    @Test
    fun f2_1_deduplicacaoTipadaApenasUmaCitacao() {
        val answer = "Sem fonte: \"$qA\" e também \"$qB\"."
        val existing = "⚠️ Revise: 1 citação fora dos trechos."
        val notice = ChatBiblicalSupport.noticeWithBiblicalSafety(
            existingNotice = existing,
            answer = answer,
            corpus = emptyList(),
            existingUnsupportedQuotes = setOf(qA),
        )
        assertNotNull(notice)
        assertTrue(notice!!.contains("“$qB”"))
        assertFalse(notice.contains("“$qA”"))
        assertTrue(notice.contains(existing))
    }

    @Test
    fun f3_consistenciaDasOcorrenciasLiterais() {
        val text = "\"$qA\" e \"$qB\""
        val raw = com.bettertalker.app.data.llm.GroundednessVerifier.factualQuoteOccurrences(text)
        assertEquals(listOf(qA, qB), raw.map { it.text })
        val unsupported = com.bettertalker.app.data.llm.GroundednessVerifier.unsupportedQuoteOccurrences(
            text, listOf(qA),
        )
        assertEquals(listOf(qB), unsupported.map { it.text })
    }
}
