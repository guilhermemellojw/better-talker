package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.LlmResponse
import com.bettertalker.app.data.llm.LlmResponseMeta
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineGenerationRequest
import com.bettertalker.app.domain.planning.PublicationRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OutlineGeneratorImplTest {

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

    private fun request() = OutlineGenerationRequest(
        id = "req-1",
        theme = "A esperança da ressurreição",
        totalMinutes = 15,
        audience = Audience.GENERAL,
        angle = OutlineAngle.DOCTRINAL,
        bibleRefs = listOf("João 5:28,29"),
        publicationRefs = listOf(PublicationRef("w19.03")),
        methodPrinciples = emptyList(),
    )

    private fun validJson() = """{"title":"T","summary":"S","sections":[
        {"title":"INTRODUÇÃO","minutes":3,"mainIdea":"Gancho","bibleRefs":["João 5:28,29"]}]}"""

    @Test
    fun validJson_returnsProposal() = runBlocking {
        val gen = OutlineGeneratorImpl(FakeLlm { okResponse(validJson()) })
        val proposal = gen.generate(request())
        assertTrue(proposal != null)
        assertEquals("T", proposal!!.title)
        assertEquals("req-1", proposal.id)
    }

    @Test
    fun invalidJson_returnsNull() = runBlocking {
        val gen = OutlineGeneratorImpl(FakeLlm { okResponse("{not json") })
        assertNull(gen.generate(request()))
    }

    @Test
    fun blankText_returnsNull() = runBlocking {
        val gen = OutlineGeneratorImpl(FakeLlm { okResponse("   ") })
        assertNull(gen.generate(request()))
    }

    @Test
    fun cancellation_propagates() = runBlocking {
        val gen = OutlineGeneratorImpl(ThrowingLlm(CancellationException("cancelled")))
        try {
            gen.generate(request())
            fail("Esperava CancellationException")
        } catch (_: CancellationException) {
            // esperado
        }
    }

    @Test
    fun genericException_returnsNull() = runBlocking {
        val gen = OutlineGeneratorImpl(ThrowingLlm(RuntimeException("boom")), log = {})
        assertNull(gen.generate(request()))
    }

    @Test
    fun requestUsesJsonSchemaFormat() = runBlocking {
        val fake = FakeLlm { okResponse(validJson()) }
        OutlineGeneratorImpl(fake).generate(request())
        assertEquals(ResponseFormat.JSON_SCHEMA, fake.received.single().responseFormat)
    }

    @Test
    fun requestCarriesNonNullSchema() = runBlocking {
        val fake = FakeLlm { okResponse(validJson()) }
        OutlineGeneratorImpl(fake).generate(request())
        assertTrue(fake.received.single().jsonSchema != null)
    }

    @Test
    fun requestTextIsBuilderPrompt() = runBlocking {
        val fake = FakeLlm { okResponse(validJson()) }
        val expected = DefaultOutlinePromptBuilder().build(request())
        OutlineGeneratorImpl(fake).generate(request())
        assertEquals(expected, fake.received.single().text)
    }

    // Opção C: validador de balanceamento de chaves/colchetes (respeita strings
    // com escape). Escolhida porque o projeto não tem parser JSON na JVM e o
    // que importa aqui é integridade estrutural do schema enviado ao Groq.
    // O schema é extraído por comportamento (capturado no LlmRequest do fake),
    // sem expor a const privada — nenhuma mudança no código de produção.
    private fun balanceOf(json: String): Pair<Int, Int> {
        var braces = 0
        var brackets = 0
        var inString = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> braces++
                    '}' -> braces--
                    '[' -> brackets++
                    ']' -> brackets--
                }
            }
            i++
        }
        return braces to brackets
    }

    private suspend fun capturedSchema(): String {
        val fake = FakeLlm { okResponse(validJson()) }
        OutlineGeneratorImpl(fake).generate(request())
        return fake.received.single().jsonSchema!!
    }

    @Test
    fun outlineSchema_bracesAndBracketsAreBalanced() = runBlocking {
        assertEquals(0 to 0, balanceOf(capturedSchema()))
    }

    @Test
    fun editProposalSchema_bracesAndBracketsAreBalanced() {
        assertEquals(
            0 to 0,
            balanceOf(com.bettertalker.app.data.llm.QwenProvider.EDIT_PROPOSAL_SCHEMA),
        )
    }
}
