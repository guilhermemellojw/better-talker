package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.LlmResponse
import com.bettertalker.app.data.llm.LlmResponseMeta
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.ResolvedBibleText
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 3.5e.1: testes do [SectionGeneratorImpl] (LLM fake, sem rede).
 */
class SectionGeneratorImplTest {

    private class FakeLlm(
        private val behavior: (LlmRequest) -> LlmResponse,
    ) : LlmProvider {
        val received = mutableListOf<LlmRequest>()
        override val id: String = "fake"
        override val model: String = "fake-model"
        override suspend fun generate(request: LlmRequest): LlmResponse {
            received.add(request)
            return behavior(request)
        }
    }

    private class ThrowingLlm(private val error: Throwable) : LlmProvider {
        override val id: String = "fake"
        override val model: String = "fake-model"
        override suspend fun generate(request: LlmRequest): LlmResponse = throw error
    }

    private fun okResponse(text: String) = LlmResponse(
        text = text,
        meta = LlmResponseMeta("fake", "fake-model", 10L, 1, offline = false),
    )

    private fun dossier() = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
            title = "Título teste", minutes = 5, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 0, updatedAt = 0,
        ),
        currentSubPoint = null,
        selectedText = null,
        fullContentHtml = "",
        overview = emptyList(),
        bibleTexts = listOf(
            ResolvedBibleText("Gên 3:6", "Texto literal", ReferenceStatus.RESOLVED)
        ),
        publicationTexts = emptyList(),
        methodPrinciples = emptyList(),
        unresolvedRefs = emptyList(),
        transitionContext = null,
    )

    private fun validJson() =
        """{"text":"<p>Como diz Gên 3:6, houve desobediência.</p>","usedSources":["Gên 3:6"]}"""

    @Test
    fun generate_validResponse_returnsDraft() = runBlocking {
        val gen = SectionGeneratorImpl(FakeLlm { okResponse(validJson()) })
        val draft = gen.generate(dossier())

        assertNotNull(draft)
        assertEquals("<p>Como diz Gên 3:6, houve desobediência.</p>", draft!!.textHtml)
        assertEquals(listOf("Gên 3:6"), draft.usedSources)
        assertTrue(draft.validation.ok)
    }

    @Test
    fun generate_blankResponse_returnsNull() = runBlocking {
        val gen = SectionGeneratorImpl(FakeLlm { okResponse("   ") })
        assertNull(gen.generate(dossier()))
    }

    @Test
    fun generate_invalidJson_returnsNull() = runBlocking {
        val gen = SectionGeneratorImpl(FakeLlm { okResponse("não é json") })
        assertNull(gen.generate(dossier()))
    }

    @Test
    fun generate_cancellation_propagates() = runBlocking {
        val gen = SectionGeneratorImpl(ThrowingLlm(CancellationException("cancelado")))
        try {
            gen.generate(dossier())
            fail("Esperava CancellationException")
        } catch (_: CancellationException) {
            // esperado
        }
    }

    @Test
    fun generate_genericException_returnsNull() = runBlocking {
        val gen = SectionGeneratorImpl(ThrowingLlm(RuntimeException("boom")))
        assertNull(gen.generate(dossier()))
    }

    @Test
    fun generate_callsLlmWithJsonSchema() = runBlocking {
        val fake = FakeLlm { okResponse(validJson()) }
        SectionGeneratorImpl(fake).generate(dossier())

        val req = fake.received.single()
        assertEquals(ResponseFormat.JSON_SCHEMA, req.responseFormat)
        assertNotNull(req.jsonSchema)
        assertTrue(req.jsonSchema!!.contains("section_draft"))
        assertEquals(1, req.maxAttempts)
    }
}
