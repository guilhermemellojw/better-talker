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
}
