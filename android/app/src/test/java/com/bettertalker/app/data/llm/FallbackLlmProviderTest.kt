package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ProviderErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeProvider(
    override val id: String,
    override val model: String = "test",
    private val behavior: (LlmRequest) -> LlmResponse,
) : LlmProvider {
    var callCount = 0
    override suspend fun generate(request: LlmRequest): LlmResponse {
        callCount++
        return behavior(request)
    }
}

private class ThrowingProvider(
    override val id: String,
    private val error: Throwable,
) : LlmProvider {
    override val model: String = "test"
    var callCount = 0
    override suspend fun generate(request: LlmRequest): LlmResponse {
        callCount++
        throw error
    }
}

class FallbackLlmProviderTest {

    private fun okResponse(text: String, providerId: String, offline: Boolean = false) = LlmResponse(
        text = text,
        meta = LlmResponseMeta(providerId, "test", 10L, 1, offline),
    )

    private fun providerError(code: ProviderErrorCode, providerId: String) = ProviderError(
        code = code,
        message = "erro $code",
        providerId = providerId,
        attempts = 1,
    )

    private fun request() = LlmRequest(text = "test")

    @Test
    fun firstProviderSucceeds_returnsItsResponse() = runBlocking {
        val p1 = FakeProvider("p1") { okResponse("um", "p1") }
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val result = FallbackLlmProvider(listOf(p1, p2)).generate(request())
        assertEquals("um", result.text)
        assertEquals("p1", result.meta.providerId)
        assertEquals(0, p2.callCount)
    }

    @Test
    fun firstRateLimit_secondSucceeds_returnsSecond() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.RATE_LIMIT, "p1"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val result = FallbackLlmProvider(listOf(p1, p2)).generate(request())
        assertEquals("dois", result.text)
        assertEquals("p2", result.meta.providerId)
    }

    @Test
    fun firstAuthenticationError_secondSucceeds_returnsSecond() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.AUTHENTICATION, "p1"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val result = FallbackLlmProvider(listOf(p1, p2)).generate(request())
        assertEquals("p2", result.meta.providerId)
    }

    @Test
    fun firstUnavailable_secondSucceeds_returnsSecond() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.UNAVAILABLE, "p1"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val result = FallbackLlmProvider(listOf(p1, p2)).generate(request())
        assertEquals("p2", result.meta.providerId)
    }

    @Test
    fun firstTimeout_secondSucceeds_returnsSecond() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.TIMEOUT, "p1"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val result = FallbackLlmProvider(listOf(p1, p2)).generate(request())
        assertEquals("p2", result.meta.providerId)
    }

    @Test
    fun firstNetwork_secondSucceeds_returnsSecond() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.NETWORK, "p1"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val result = FallbackLlmProvider(listOf(p1, p2)).generate(request())
        assertEquals("p2", result.meta.providerId)
    }

    @Test
    fun firstInvalidRequest_doesNotFallback() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.INVALID_REQUEST, "p1"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        try {
            FallbackLlmProvider(listOf(p1, p2)).generate(request())
            fail("Esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.INVALID_REQUEST, e.code)
        }
        assertEquals(0, p2.callCount)
    }

    @Test
    fun firstInvalidResponse_doesNotFallback() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.INVALID_RESPONSE, "p1"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        try {
            FallbackLlmProvider(listOf(p1, p2)).generate(request())
            fail("Esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.INVALID_RESPONSE, e.code)
        }
        assertEquals(0, p2.callCount)
    }

    @Test
    fun bothFail_throwsLastError() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.RATE_LIMIT, "p1"))
        val p2 = ThrowingProvider("p2", providerError(ProviderErrorCode.UNAVAILABLE, "p2"))
        try {
            FallbackLlmProvider(listOf(p1, p2)).generate(request())
            fail("Esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.UNAVAILABLE, e.code)
            assertEquals("p2", e.providerId)
        }
    }

    @Test
    fun cancellation_doesNotFallback_propagatesImmediately() = runBlocking {
        val p1 = ThrowingProvider("p1", CancellationException("cancelled"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        try {
            FallbackLlmProvider(listOf(p1, p2)).generate(request())
            fail("Esperava CancellationException")
        } catch (_: CancellationException) {
            // esperado
        }
        assertEquals(0, p2.callCount)
    }

    @Test
    fun blankResponse_fallsBackToSecond() = runBlocking {
        val p1 = FakeProvider("p1") { okResponse("", "p1") }
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val result = FallbackLlmProvider(listOf(p1, p2)).generate(request())
        assertEquals("dois", result.text)
        assertEquals("p2", result.meta.providerId)
    }

    @Test
    fun offlineResponse_isTerminalSuccess() = runBlocking {
        val p1 = FakeProvider("p1") { okResponse("offline text", "p1", offline = true) }
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val result = FallbackLlmProvider(listOf(p1, p2)).generate(request())
        assertEquals("offline text", result.text)
        assertTrue(result.meta.offline)
        assertEquals(0, p2.callCount)
    }

    @Test
    fun emptyProvidersList_throwsUnavailable() = runBlocking {
        try {
            FallbackLlmProvider(emptyList()).generate(request())
            fail("Esperava ProviderError")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.UNAVAILABLE, e.code)
        }
    }

    @Test
    fun allProvidersCircuitBroken_stillTriesFirst() = runBlocking {
        var now = 0L
        val breaker = CircuitBreaker(clock = { now })
        val p1 = FakeProvider("p1") { okResponse("um", "p1") }
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val fallback = FallbackLlmProvider(listOf(p1, p2), breaker)
        repeat(3) { breaker.recordFailure("p1") }
        repeat(3) { breaker.recordFailure("p2") }
        assertTrue(breaker.shouldSkip("p1"))
        assertTrue(breaker.shouldSkip("p2"))
        val result = fallback.generate(request())
        assertEquals("um", result.text)
        assertTrue(p1.callCount >= 1)
    }

    @Test
    fun circuitBreakerSkipsProviderAfterFailures() = runBlocking {
        val p1 = ThrowingProvider("p1", providerError(ProviderErrorCode.RATE_LIMIT, "p1"))
        val p2 = FakeProvider("p2") { okResponse("dois", "p2") }
        val fallback = FallbackLlmProvider(listOf(p1, p2))
        repeat(3) { fallback.generate(request()) }
        val callsAfterThree = p1.callCount
        fallback.generate(request())
        assertEquals(callsAfterThree, p1.callCount)
        assertTrue(p2.callCount >= 4)
    }
}
