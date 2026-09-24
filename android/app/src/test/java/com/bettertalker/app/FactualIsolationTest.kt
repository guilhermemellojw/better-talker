package com.bettertalker.app

import com.bettertalker.app.data.domain.ContextPacks
import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.repo.CopilotRepository
import com.bettertalker.app.data.repo.contentHits
import com.bettertalker.app.data.repo.partitionGuideHits
import com.bettertalker.app.data.db.PassageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun cand(id: String, pub: String, type: SourceType) = RetrievalCandidate(
    Passage(id, pub, "texto $id", "texto $id", "", "", null, null, 0, type, null, TrainingCategory.UNKNOWN),
    pub, 0.8, 0.2, 0.8, listOf("texto"), listOf("lexical")
)

class FactualIsolationTest {

    @Test
    fun factualPackHasEmptyTraining() {
        val pack = ContextPacks.contentOnly(
            listOf(cand("p1", "w", SourceType.CONTENT), cand("p2", "w", SourceType.CONTENT))
        )
        assertEquals(2, pack.contentSources.size)
        assertTrue(pack.trainingSources.isEmpty())
    }

    @Test
    fun presentationPackKeepsTracksSeparate() {
        val pack = ContextPacks.fromCandidates(
            listOf(cand("p1", "w", SourceType.CONTENT)),
            listOf(cand("p2", "be", SourceType.TRAINING))
        )
        assertEquals(1, pack.contentSources.size)
        assertEquals(1, pack.trainingSources.size)
        assertTrue(pack.contentSources.all { it.sourceType == SourceType.CONTENT })
        assertTrue(pack.trainingSources.all { it.sourceType == SourceType.TRAINING })
    }

    @Test
    fun legacyPartitionStillSeparatesGuide() {
        val be = PassageEntity("g1", "be", "guia", "guia")
        val w = PassageEntity("c1", "w", "materia", "materia")
        val hits = listOf(
            com.bettertalker.app.data.repo.ScopedHit(be, "Beneficie-se da Escola do Ministério Teocrático · X"),
            com.bettertalker.app.data.repo.ScopedHit(w, "A Sentinela")
        )
        val (guide, content) = partitionGuideHits(hits)
        assertEquals(1, guide.size)
        assertEquals(1, content.size)
        assertEquals("c1", contentHits(hits).first().passage.id)
    }

    @Test
    fun exampleKindEnumUnchanged() {
        assertEquals(4, CopilotRepository.ExampleKind.values().size)
    }
}
