package com.bettertalker.app

import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.verify.ClaimType
import com.bettertalker.app.data.verify.SupportStatus
import com.bettertalker.app.data.verify.extractClaims
import com.bettertalker.app.data.verify.extractNumbers
import com.bettertalker.app.data.verify.splitCompound
import com.bettertalker.app.data.verify.verifyText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 18 — BLOCO B. Testes do F6 (§35): claims, números, referências,
 * evidência, insufficient, partial, creative, interpretive cap, proveniência.
 */
class VerificationTest {

    private fun cand(
        id: String,
        text: String,
        normalized: String = text.lowercase(),
        ref: String = "",
        lexical: Double = 0.8,
        matched: List<String> = listOf("confianca", "jeova"),
        section: String = "",
        pubTitle: String? = "A Sentinela"
    ) = RetrievalCandidate(
        passage = Passage(id, "pub1", text, normalized, ref, section, null, null, 0,
            SourceType.CONTENT, null, TrainingCategory.UNKNOWN),
        publicationTitle = pubTitle,
        lexicalScore = lexical,
        metadataScore = 0.5,
        finalScore = lexical,
        matchedTerms = matched,
        foundBy = listOf("lexical")
    )

    // ---------- claims ----------

    @Test
    fun extraiSentencasSimples() {
        val claims = extractClaims("A confiança sustenta. A oração fortalece.")
        assertEquals(2, claims.size)
        assertEquals(ClaimType.FACTUAL, claims[0].type)
    }

    @Test
    fun naoParteNumeroComPonto() {
        // "w24.01" não pode virar dois claims.
        val claims = extractClaims("Ver w24.01 página 5 para mais detalhes importantes.")
        assertEquals(1, claims.size)
    }

    @Test
    fun perguntaViraRhetorical() {
        val claims = extractClaims("O que significa confiar?")
        assertEquals(1, claims.size)
        assertEquals(ClaimType.RHETORICAL, claims[0].type)
    }

    @Test
    fun aplicacaoPessoalEViraApplication() {
        val claims = extractClaims("Podemos aplicar isso hoje em nossa vida diária.")
        assertEquals(ClaimType.APPLICATION, claims[0].type)
    }

    @Test
    fun limiteDeClaimsRespeitado() {
        val text = (1..30).joinToString(" ") { "Afirmação número $it sobre coisas importantes." }
        assertTrue(extractClaims(text).size <= 20)
    }

    @Test
    fun splitCompoundDivideEmE() {
        val parts = splitCompound("A oração fortalece e A confiança cresce sempre.", 0)
        assertEquals(2, parts.size)
    }

    @Test
    fun extractNumbersNormaliza() {
        assertEquals(listOf("42"), extractNumbers("são 42 pessoas"))
        assertEquals(listOf("50%"), extractNumbers("cerca de 50% dos casos"))
    }

    // ---------- creative ----------

    @Test
    fun rhetoricalNaoPassaPorRetrieval() = runBlocking {
        var called = false
        val v = verifyText("O que significa confiar de verdade?", retrieve = { _, _ ->
            called = true
            emptyList()
        })
        assertEquals(SupportStatus.CREATIVE, v.claims[0].status)
        assertEquals(1, v.summary.creative)
        assertTrue(!called)
    }

    // ---------- insufficient ----------

    @Test
    fun semEvidenciaEInsufficient() = runBlocking {
        val v = verifyText("A confiança sustenta quem enfrenta provação real.",
            retrieve = { _, _ -> emptyList() })
        assertEquals(SupportStatus.INSUFFICIENT, v.claims[0].status)
        assertTrue(v.claims[0].reason.contains("Nenhuma evidência"))
        assertEquals(1, v.summary.insufficient)
    }

    // ---------- supported ----------

    @Test
    fun evidênciaForteESupported() = runBlocking {
        val v = verifyText("A confiança em Jeová sustenta.",
            retrieve = { _, _ ->
                listOf(cand("c1", "A confiança em Jeová sustenta os leais",
                    "a confianca em jeova sustenta os leais",
                    matched = listOf("confianca", "jeova", "sustenta")))
            })
        assertEquals(SupportStatus.SUPPORTED, v.claims[0].status)
        assertTrue(v.claims[0].reason.startsWith("Sustentado por:"))
        assertEquals(1, v.summary.supported)
        // Proveniência real na evidência.
        assertEquals("A Sentinela", v.claims[0].evidence[0].reference.ifBlank { "A Sentinela" })
    }

    // ---------- partial por número ----------

    @Test
    fun numeroAusenteCaiParaPartial() = runBlocking {
        val v = verifyText("Eram 42 pessoas presentes naquele dia especial.",
            retrieve = { _, _ ->
                listOf(cand("c1", "muitas pessoas presentes naquele dia",
                    "muitas pessoas presentes naquele dia"))
            })
        assertEquals(SupportStatus.PARTIALLY_SUPPORTED, v.claims[0].status)
        assertTrue(v.claims[0].reason.contains("42"))
    }

    // ---------- interpretive cap ----------

    @Test
    fun interpretiveNuncaESupportedDireto() = runBlocking {
        val v = verifyText("Isso significa que a confiança sempre vence tudo.",
            retrieve = { _, _ ->
                listOf(cand("c1", "isso significa que a confianca sempre vence tudo",
                    "isso significa que a confianca sempre vence tudo",
                    matched = listOf("isso", "significa", "confianca", "sempre", "vence", "tudo")))
            })
        assertEquals(SupportStatus.PARTIALLY_SUPPORTED, v.claims[0].status)
        assertTrue(v.claims[0].reason.contains("teto parcial"))
    }

    // ---------- training ----------

    @Test
    fun trainingForteESupported() = runBlocking {
        val v = verifyText("Use ilustrações para abrir a introdução com impacto.",
            retrieve = { _, training ->
                assertTrue(training)
                listOf(cand("t1", "ilustracoes abrem a introducao", "ilustracoes abrem a introducao"))
            })
        // Tipo training: trilha de treinamento, não factual.
        assertEquals(ClaimType.TRAINING, v.claims[0].claim.type)
        assertEquals(SupportStatus.SUPPORTED, v.claims[0].status)
    }

    @Test
    fun trainingSemHitEInsufficient() = runBlocking {
        val v = verifyText("Module a voz no palco com técnica.",
            retrieve = { _, _ -> emptyList() })
        assertEquals(SupportStatus.INSUFFICIENT, v.claims[0].status)
        assertTrue(v.claims[0].reason.contains("BE/TH"))
    }

    // ---------- resumo ----------

    @Test
    fun resumoContaPorEstado() = runBlocking {
        val v = verifyText("A confiança sustenta. O que significa isso? Podemos aplicar hoje.",
            retrieve = { _, _ -> emptyList() })
        assertEquals(3, v.claims.size)
        assertEquals(1, v.summary.insufficient)
        // rhetorical + application = creative.
        assertEquals(2, v.summary.creative)
        assertTrue(v.limited)
        assertTrue(v.key.isNotBlank())
    }
}
