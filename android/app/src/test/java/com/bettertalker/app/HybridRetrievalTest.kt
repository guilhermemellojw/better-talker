package com.bettertalker.app

import com.bettertalker.app.data.domain.HybridRetrieval
import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun norm(s: String): String = s.lowercase()
    .replace(Regex("[àáâãä]"), "a").replace(Regex("[éêë]"), "e")
    .replace(Regex("[íîï]"), "i").replace(Regex("[óôõö]"), "o")
    .replace(Regex("[úûü]"), "u").replace(Regex("[ç]"), "c")
    .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

private fun passage(
    id: String,
    pub: String,
    text: String,
    type: SourceType = SourceType.CONTENT,
    ref: String = "",
    section: String = "",
    cat: TrainingCategory = TrainingCategory.UNKNOWN
) = Passage(id, pub, text, norm(text), ref, section, null, null, 0, type, null, cat)

class HybridRetrievalTest {

    private val content = listOf(
        passage("p1", "w", "Confiar em Jeová ajuda a enfrentar problemas graves com coragem."),
        passage("p2", "w", "A oração sincera fortalece a amizade com Deus todos os dias.", ref = "w24 Oração §5"),
        passage("p3", "w", "Estudar a Bíblia em família cria momentos de alegria.")
    )
    private val titles = mapOf("w" to "A Sentinela")

    @Test
    fun exactMatchRanksFirst() {
        val hits = HybridRetrieval.rank(
            "Confiar em Jeová ajuda a enfrentar problemas graves com coragem.",
            content, titles, 5
        )
        assertEquals("p1", hits.first().passage.id)
        assertTrue(hits.first().foundBy.contains("lexical"))
        assertTrue(hits.first().matchedTerms.isNotEmpty())
    }

    @Test
    fun scoresInRangeAndDeterministic() {
        val a = HybridRetrieval.rank("oração Jeová Deus", content, titles, 5)
        val b = HybridRetrieval.rank("oração Jeová Deus", content, titles, 5)
        assertEquals(a.map { it.passage.id }, b.map { it.passage.id })
        assertEquals(a.map { it.finalScore }, b.map { it.finalScore })
        for (h in a) {
            assertTrue(h.finalScore in 0.0..1.0)
            assertTrue(h.lexicalScore in 0.0..1.0)
            assertTrue(h.metadataScore in 0.0..1.0)
        }
    }

    @Test
    fun emptyQueryOrCandidatesGivesEmpty() {
        assertTrue(HybridRetrieval.rank("", content, titles, 5).isEmpty())
        assertTrue(HybridRetrieval.rank("oração", emptyList(), titles, 5).isEmpty())
        assertTrue(HybridRetrieval.rank("oração", content, titles, 0).isEmpty())
    }

    @Test
    fun metadataSymbolBoosts() {
        val cands = listOf(
            passage("a", "x", "Texto genérico sobre vários assuntos."),
            passage("b", "w24", "Texto genérico sobre vários assuntos.", ref = "w24 Seção §1")
        )
        val hits = HybridRetrieval.rank("oração w24", cands, mapOf("x" to "Avulso", "w24" to "Revista"), 5)
        assertTrue(hits.isNotEmpty())
        assertEquals("b", hits.first().passage.id)
        assertTrue(hits.first().metadataScore > 0.0)
    }

    @Test
    fun categoryBoostReordersWithoutTouchingScores() {
        fun cand(id: String, score: Double) = RetrievalCandidate(
            passage("p-$id", "be", "t $id", SourceType.TRAINING, cat = TrainingCategory.ILLUSTRATION),
            null, score, 0.0, score, emptyList(), listOf("lexical")
        )
        val other = cand("other", 0.80).copy(
            passage = passage("p-other", "be", "t other", SourceType.TRAINING, cat = TrainingCategory.DELIVERY)
        )
        val hits = HybridRetrieval.applyCategoryBoost(
            listOf(other, cand("illus", 0.70)),
            TrainingCategory.ILLUSTRATION
        ) { it.passage.trainingCategory }
        // 0.70 + 0.15 > 0.80: intenção vence; scores intactos.
        assertEquals("p-illus", hits.first().passage.id)
        assertEquals(0.70, hits.first().finalScore, 0.0)
        assertEquals(0.80, hits[1].finalScore, 0.0)
    }

    @Test
    fun categoryMismatchKeepsItemsAfter() {
        fun cand(id: String, score: Double, cat: TrainingCategory) = RetrievalCandidate(
            passage("p-$id", "be", "t", SourceType.TRAINING, cat = cat),
            null, score, 0.0, score, emptyList(), listOf("lexical")
        )
        val hits = HybridRetrieval.applyCategoryBoost(
            listOf(cand("a", 0.9, TrainingCategory.DELIVERY), cand("b", 0.5, TrainingCategory.ILLUSTRATION)),
            TrainingCategory.ILLUSTRATION
        ) { it.passage.trainingCategory }
        // 0.5+0.15=0.65 < 0.9: fora da categoria continua depois, não some.
        assertEquals(listOf("p-a", "p-b"), hits.map { it.passage.id })
    }
}
