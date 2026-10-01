package com.bettertalker.app.data.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Circuit breaker simples, em memória, por provider.
 *
 * Após [failureThreshold] falhas consecutivas, o provider fica "aberto"
 * (pulado) por [openDurationMs]. Um sucesso zera o contador.
 *
 * Não persistente. Thread-safe via Mutex.
 */
internal class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 5 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val failures = mutableMapOf<String, Int>()
    private val openedAt = mutableMapOf<String, Long>()

    suspend fun shouldSkip(providerId: String): Boolean = mutex.withLock {
        val opened = openedAt[providerId] ?: return@withLock false
        if (clock() - opened >= openDurationMs) {
            openedAt.remove(providerId)
            failures.remove(providerId)
            false
        } else {
            true
        }
    }

    suspend fun recordFailure(providerId: String) = mutex.withLock {
        val count = (failures[providerId] ?: 0) + 1
        failures[providerId] = count
        if (count >= failureThreshold) {
            openedAt[providerId] = clock()
        }
    }

    suspend fun recordSuccess(providerId: String) = mutex.withLock {
        failures.remove(providerId)
        openedAt.remove(providerId)
    }
}
