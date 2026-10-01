package com.bettertalker.app.data.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitBreakerTest {

    private var now = 0L
    private fun breaker() = CircuitBreaker(clock = { now })

    @Test
    fun freshBreaker_shouldNotSkip() = runBlocking {
        assertFalse(breaker().shouldSkip("p1"))
    }

    @Test
    fun afterThresholdFailures_shouldSkip() = runBlocking {
        val b = breaker()
        b.recordFailure("p1")
        b.recordFailure("p1")
        b.recordFailure("p1")
        assertTrue(b.shouldSkip("p1"))
    }

    @Test
    fun afterWindowExpires_shouldNotSkip() = runBlocking {
        val b = breaker()
        b.recordFailure("p1")
        b.recordFailure("p1")
        b.recordFailure("p1")
        now += 5 * 60 * 1000L
        assertFalse(b.shouldSkip("p1"))
    }

    @Test
    fun successResetsCounter() = runBlocking {
        val b = breaker()
        b.recordFailure("p1")
        b.recordFailure("p1")
        b.recordSuccess("p1")
        b.recordFailure("p1")
        b.recordFailure("p1")
        assertFalse(b.shouldSkip("p1"))
    }

    @Test
    fun failuresBelowThreshold_shouldNotSkip() = runBlocking {
        val b = breaker()
        b.recordFailure("p1")
        b.recordFailure("p1")
        assertFalse(b.shouldSkip("p1"))
    }

    @Test
    fun differentProvidersAreIndependent() = runBlocking {
        val b = breaker()
        b.recordFailure("p1")
        b.recordFailure("p1")
        b.recordFailure("p1")
        assertTrue(b.shouldSkip("p1"))
        assertFalse(b.shouldSkip("p2"))
    }
}
