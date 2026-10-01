package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.planning.retrieval.BibleIndex
import com.bettertalker.app.domain.planning.retrieval.MethodIndex
import com.bettertalker.app.domain.planning.retrieval.PublicationIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeBibleIndex(private val refs: List<String> = emptyList()) : BibleIndex {
    var callCount = 0
    var lastLimit = -1
    override suspend fun findByTheme(theme: String, limit: Int): List<String> {
        callCount++
        lastLimit = limit
        return refs
    }
}

private class FakePublicationIndex(private val refs: List<PublicationRef> = emptyList()) : PublicationIndex {
    var callCount = 0
    override suspend fun findByTheme(theme: String, limit: Int): List<PublicationRef> {
        callCount++
        return refs
    }
}

private class FakeMethodIndex(private val principles: List<String> = emptyList()) : MethodIndex {
    var callCount = 0
    override suspend fun findPrinciples(
        context: String,
        limit: Int,
        category: String?,
    ): List<String> {
        callCount++
        return principles
    }
}

private class FakeGenerator(
    private val behavior: (OutlineGenerationRequest) -> OutlineProposal?,
) : OutlineGenerator {
    val receivedRequests = mutableListOf<OutlineGenerationRequest>()
    override suspend fun generate(request: OutlineGenerationRequest): OutlineProposal? {
        receivedRequests.add(request)
        return behavior(request)
    }
}

class OutlineProposerTest {

    private class Fixture(
        val proposer: OutlineProposer,
        val generator: FakeGenerator,
        val bible: FakeBibleIndex,
        val publications: FakePublicationIndex,
        val methods: FakeMethodIndex,
    )

    private fun fixtureWith(
        bibleRefs: List<String> = emptyList(),
        publicationRefs: List<PublicationRef> = emptyList(),
        principles: List<String> = emptyList(),
        behavior: (OutlineGenerationRequest) -> OutlineProposal?,
        idProvider: () -> String = { "id-${System.nanoTime()}" },
    ): Fixture {
        val bible = FakeBibleIndex(bibleRefs)
        val pubs = FakePublicationIndex(publicationRefs)
        val methods = FakeMethodIndex(principles)
        val generator = FakeGenerator(behavior)
        val proposer = OutlineProposer(bible, pubs, methods, generator, idProvider)
        return Fixture(proposer, generator, bible, pubs, methods)
    }

    private fun validProposal(
        id: String,
        angle: OutlineAngle,
        totalMinutes: Int = 15,
    ) = OutlineProposal(
        id = id,
        angle = angle,
        audience = Audience.GENERAL,
        title = "Título $angle",
        summary = "Resumo $angle",
        totalMinutes = totalMinutes,
        sections = listOf(
            SectionPlan("INTRODUÇÃO", 3, "Gancho", bibleRefs = listOf("Jó 14:1")),
            SectionPlan("DESENVOLVIMENTO", 7, "Corpo", bibleRefs = listOf("João 5:28")),
            SectionPlan("CONCLUSÃO", 5, "Fecho", bibleRefs = listOf("Apo 21:4")),
        ),
    )

    private fun invalidProposal(id: String, angle: OutlineAngle) = validProposal(id, angle).copy(title = "")

    @Test
    fun allAnglesValid_returnsThreeProposals() = runBlocking {
        val fixture = fixtureWith(behavior = { validProposal(it.id, it.angle) })
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(3, result.size)
        assertEquals(
            listOf(OutlineAngle.DOCTRINAL, OutlineAngle.PRACTICAL, OutlineAngle.NARRATIVE),
            result.map { it.angle },
        )
    }

    @Test
    fun oneAngleInvalid_isSkipped() = runBlocking {
        val fixture = fixtureWith(
            behavior = { request ->
                if (request.angle == OutlineAngle.NARRATIVE) invalidProposal(request.id, request.angle)
                else validProposal(request.id, request.angle)
            },
        )
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(2, result.size)
        assertEquals(
            listOf(OutlineAngle.DOCTRINAL, OutlineAngle.PRACTICAL),
            result.map { it.angle },
        )
    }

    @Test
    fun allAnglesInvalid_returnsEmpty() = runBlocking {
        val fixture = fixtureWith(behavior = { invalidProposal(it.id, it.angle) })
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertTrue(result.isEmpty())
    }

    @Test
    fun oneAngleReturnsNull_isSkipped() = runBlocking {
        val fixture = fixtureWith(
            behavior = { request ->
                if (request.angle == OutlineAngle.PRACTICAL) null
                else validProposal(request.id, request.angle)
            },
        )
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(2, result.size)
        assertEquals(
            listOf(OutlineAngle.DOCTRINAL, OutlineAngle.NARRATIVE),
            result.map { it.angle },
        )
    }

    @Test
    fun oneAngleThrows_isSkipped() = runBlocking {
        val fixture = fixtureWith(
            behavior = { request ->
                if (request.angle == OutlineAngle.PRACTICAL) throw RuntimeException("boom")
                else validProposal(request.id, request.angle)
            },
        )
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(2, result.size)
        assertEquals(
            listOf(OutlineAngle.DOCTRINAL, OutlineAngle.NARRATIVE),
            result.map { it.angle },
        )
    }

    @Test
    fun allAnglesThrow_returnsEmpty() = runBlocking {
        val fixture = fixtureWith(behavior = { throw RuntimeException("boom") })
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertTrue(result.isEmpty())
    }

    @Test
    fun idsAreUnique() = runBlocking {
        val fixture = fixtureWith(behavior = { validProposal(it.id, it.angle) })
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(3, result.map { it.id }.toSet().size)
    }

    @Test
    fun idProviderIsUsed() = runBlocking {
        var counter = 0
        val fixture = fixtureWith(
            behavior = { validProposal(it.id, it.angle) },
            idProvider = { "id-${counter++}" },
        )
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(listOf("id-0", "id-1", "id-2"), result.map { it.id })
    }

    @Test
    fun retrievalCalledOnceEach() = runBlocking {
        val fixture = fixtureWith(behavior = { validProposal(it.id, it.angle) })
        fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(1, fixture.bible.callCount)
        assertEquals(1, fixture.publications.callCount)
        assertEquals(1, fixture.methods.callCount)
    }

    @Test
    fun retrievalLimitsAreCorrect() = runBlocking {
        val fixture = fixtureWith(behavior = { validProposal(it.id, it.angle) })
        fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(20, fixture.bible.lastLimit)
    }

    @Test
    fun retrievalResultsPassedToGenerator() = runBlocking {
        val bibleRefs = listOf("Gê 1:1")
        val pubRefs = listOf(PublicationRef("w19.03"))
        val principles = listOf("princípio X")
        val fixture = fixtureWith(
            bibleRefs = bibleRefs,
            publicationRefs = pubRefs,
            principles = principles,
            behavior = { validProposal(it.id, it.angle) },
        )
        fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(3, fixture.generator.receivedRequests.size)
        for (request in fixture.generator.receivedRequests) {
            assertEquals(bibleRefs, request.bibleRefs)
            assertEquals(pubRefs, request.publicationRefs)
            assertEquals(principles, request.methodPrinciples)
        }
    }

    @Test
    fun generatorCalledThreeTimes() = runBlocking {
        val fixture = fixtureWith(behavior = { validProposal(it.id, it.angle) })
        fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(3, fixture.generator.receivedRequests.size)
    }

    @Test
    fun requestFieldsAreCorrect() = runBlocking {
        val fixture = fixtureWith(behavior = { validProposal(it.id, it.angle) })
        fixture.proposer.propose("fé", 30, Audience.YOUNG)
        assertEquals(3, fixture.generator.receivedRequests.size)
        val angles = listOf(OutlineAngle.DOCTRINAL, OutlineAngle.PRACTICAL, OutlineAngle.NARRATIVE)
        fixture.generator.receivedRequests.forEachIndexed { index, request ->
            assertEquals("fé", request.theme)
            assertEquals(30, request.totalMinutes)
            assertEquals(Audience.YOUNG, request.audience)
            assertEquals(angles[index], request.angle)
        }
    }

    @Test
    fun emptyRetrieval_stillGenerates() = runBlocking {
        val fixture = fixtureWith(behavior = { validProposal(it.id, it.angle) })
        val result = fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(3, result.size)
    }

    @Test
    fun generatorCancels_propagatesCancellation() = runBlocking {
        val fixture = fixtureWith(
            behavior = { request ->
                if (request.angle == OutlineAngle.PRACTICAL) {
                    throw CancellationException("cancelled")
                } else {
                    validProposal(request.id, request.angle)
                }
            },
        )
        try {
            fixture.proposer.propose("ressurreição", 15, Audience.GENERAL)
            fail("Esperava CancellationException")
        } catch (_: CancellationException) {
            // esperado — proposer não engole cancelamento
        }
    }
}
