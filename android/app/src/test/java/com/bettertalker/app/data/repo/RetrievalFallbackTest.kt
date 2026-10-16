package com.bettertalker.app.data.repo

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.RetrievalResult
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.expandQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #15d — fallback adaptativo do ContextPack. Fakes, sem Room/Android.
 * Regras: ≥ MIN_RELEVANT_HITS relevantes → Top-K 5 normal; abaixo →
 * query expandida + Top-K 10, preservando os hits base.
 */
class RetrievalFallbackTest {

    private fun hit(id: String, score: Double, terms: List<String> = emptyList()): RetrievalCandidate =
        RetrievalCandidate(
            passage = Passage(
                id = id,
                pubId = "att-it",
                text = "texto $id",
                normalizedText = "texto $id",
                ref = "it Teste §$id",
                section = "TESTE",
                page = null,
                paragraph = null,
                order = 1,
                sourceType = SourceType.CONTENT,
                symbol = "it",
                trainingCategory = TrainingCategory.UNKNOWN,
            ),
            publicationTitle = "it_T.jwpub",
            lexicalScore = score,
            metadataScore = 0.0,
            finalScore = score,
            matchedTerms = terms,
            foundBy = listOf("lexical"),
        )

    private class FakeRetrieval(
        private val byQuery: Map<String, List<RetrievalCandidate>>
    ) : RetrievalRepository {
        /** (query, limit) por chamada. */
        val calls = mutableListOf<Pair<String, Int>>()
        override suspend fun retrieve(query: String, scope: RetrievalScope, limit: Int): RetrievalResult {
            calls += query to limit
            return RetrievalResult(RetrievalStatus.OK, byQuery[query] ?: emptyList())
        }
    }

    private class NoTraining : TrainingRepository {
        override suspend fun retrieveTraining(
            query: String,
            category: TrainingCategory?,
            scope: RetrievalScope,
            limit: Int
        ): RetrievalResult = RetrievalResult(RetrievalStatus.OK, emptyList())
    }

    private fun pack(
        query: String,
        byQuery: Map<String, List<RetrievalCandidate>>
    ): Pair<ContextPack, FakeRetrieval> {
        val fake = FakeRetrieval(byQuery)
        val repo = RoomContextPackRepository(fake, NoTraining(), log = {})
        val out = runBlocking {
            repo.buildPack(query, RetrievalScope(listOf("att-it"), emptyList()), false, null)
        }
        return out to fake
    }

    @Test
    fun hitsRelevantes_semExpansao_comportamentoAtual() {
        // #15d-fix: relevantes exigem score >= 0.15 E >= 2 termos casados.
        val base = listOf(
            hit("1", 0.5, listOf("morte", "alma")),
            hit("2", 0.4, listOf("morte", "ressurreicao")),
            hit("3", 0.3, listOf("alma", "seol")),
        )
        val (pack, fake) = pack("morte", mapOf("morte" to base))
        assertEquals(1, fake.calls.size) // sem 2ª chamada
        assertEquals(5, fake.calls[0].second) // Top-K padrão preservado
        assertEquals(3, pack.contentSources.size)
    }

    @Test
    fun hitsFracos_ativaFallback_queryExpandida_topK10() {
        val base = listOf(hit("1", 0.05)) // 0 relevantes < 3
        val expandedQuery = expandQuery("mortos inconscientes")
        val expanded = listOf(hit("1", 0.9), hit("2", 0.8), hit("3", 0.7))
        val (_, fake) = pack(
            "mortos inconscientes",
            mapOf("mortos inconscientes" to base, expandedQuery to expanded),
        )
        assertEquals(2, fake.calls.size)
        val (q2, limit2) = fake.calls[1]
        assertTrue(q2.contains("morte"))
        assertTrue(q2.contains("alma"))
        assertEquals(10, limit2)
    }

    @Test
    fun fallback_preservaHitsBase() {
        val base = listOf(hit("base-1", 0.10))
        val expandedQuery = expandQuery("mortos inconscientes")
        val expanded = listOf(hit("novo-1", 0.9))
        val (pack, _) = pack(
            "mortos inconscientes",
            mapOf("mortos inconscientes" to base, expandedQuery to expanded),
        )
        val ids = pack.contentSources.map { it.id }
        assertTrue(ids.contains("base-1")) // hit base preservado (nunca substituído)
        assertTrue(ids.contains("novo-1"))
    }

    @Test
    fun scoreMinimo_filtraRuidoDaExpansao() {
        val base = listOf(hit("base-1", 0.05))
        val expandedQuery = expandQuery("mortos inconscientes")
        val expanded = listOf(hit("ruido", 0.01), hit("bom", 0.5))
        val (pack, _) = pack(
            "mortos inconscientes",
            mapOf("mortos inconscientes" to base, expandedQuery to expanded),
        )
        val ids = pack.contentSources.map { it.id }
        assertFalse(ids.contains("ruido")) // < MIN_RELEVANT_SCORE
        assertTrue(ids.contains("bom"))
    }

    // ---------- #15d-fix: gate exige score + cobertura >= 2 ----------

    @Test
    fun casoQ3_cincoHitsUmMatchCada_ativaFallback() {
        // O caso real: 5 versículos com 0.225 mas casando SÓ "explicar".
        val base = (1..5).map { hit("v$it", 0.225, listOf("explicar")) }
        val expandedQuery = expandQuery("mortos inconscientes")
        val expanded = listOf(hit("novo", 0.9, listOf("morte", "alma")))
        val (_, fake) = pack(
            "mortos inconscientes",
            mapOf("mortos inconscientes" to base, expandedQuery to expanded),
        )
        assertEquals(2, fake.calls.size) // relevant = 0 < 3 → fallback dispara
    }

    @Test
    fun tresHitsDoisMatchesCada_semFallback() {
        val base = (1..3).map { hit("ok$it", 0.25, listOf("mortos", "inconscientes")) }
        val (_, fake) = pack("mortos inconscientes", mapOf("mortos inconscientes" to base))
        assertEquals(1, fake.calls.size) // relevant = 3 → comportamento atual
    }

    @Test
    fun mixUmForteQuatroFracos_ativaFallback() {
        val base = listOf(hit("forte", 0.25, listOf("mortos", "inconscientes"))) +
            (1..4).map { hit("fraco$it", 0.20, listOf("explicar")) }
        val expandedQuery = expandQuery("mortos inconscientes")
        val (_, fake) = pack(
            "mortos inconscientes",
            mapOf("mortos inconscientes" to base, expandedQuery to emptyList()),
        )
        assertEquals(2, fake.calls.size) // relevant = 1 < 3 → fallback dispara
    }

    @Test
    fun scoreAltoSemMatches_naoConta() {
        // Piso de source sozinho (sem termos casados) não é relevante.
        val base = listOf(
            hit("piso1", 0.35),
            hit("piso2", 0.30),
            hit("piso3", 0.25),
        )
        val expandedQuery = expandQuery("mortos inconscientes")
        val (_, fake) = pack(
            "mortos inconscientes",
            mapOf("mortos inconscientes" to base, expandedQuery to emptyList()),
        )
        assertEquals(2, fake.calls.size) // relevant = 0 → fallback dispara
    }
}
