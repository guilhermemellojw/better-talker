package com.bettertalker.app.domain.coach

/**
 * Relógio puro da sessão de análise: separa tempo total (com pausas) do tempo
 * efetivo de escuta (base do wpm). O clock é injetável para teste.
 */
class SpeechSessionTimer(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var sessionStartMs: Long = 0L
    private var listeningStartMs: Long = 0L
    private var accumulatedMs: Long = 0L
    private var running: Boolean = false
    private var started: Boolean = false

    val isRunning: Boolean get() = running

    fun start() {
        sessionStartMs = clock()
        listeningStartMs = sessionStartMs
        accumulatedMs = 0L
        running = true
        started = true
    }

    fun pause() {
        if (!running) return
        accumulatedMs += (clock() - listeningStartMs).coerceAtLeast(0L)
        running = false
    }

    fun resume() {
        if (running || !started) return
        listeningStartMs = clock()
        running = true
    }

    fun speechSeconds(): Long {
        val extra = if (running) (clock() - listeningStartMs).coerceAtLeast(0L) else 0L
        return (accumulatedMs + extra) / 1_000L
    }

    fun sessionSeconds(): Long {
        if (!started) return 0L
        return ((clock() - sessionStartMs).coerceAtLeast(0L)) / 1_000L
    }

    fun reset() {
        sessionStartMs = 0L
        listeningStartMs = 0L
        accumulatedMs = 0L
        running = false
        started = false
    }
}
