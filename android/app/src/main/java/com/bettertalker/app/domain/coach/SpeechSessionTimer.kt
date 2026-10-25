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
    private var speechStarted: Boolean = false

    val isRunning: Boolean get() = running

    /** Começa o relógio da SESSÃO; o de fala espera a primeira fala. */
    fun start() {
        sessionStartMs = clock()
        listeningStartMs = 0L
        accumulatedMs = 0L
        running = false
        started = true
        speechStarted = false
    }

    /** Marca a primeira fala sem iniciar a contagem (ex.: pausado). */
    fun markSpeechStarted() {
        if (speechStarted) return
        speechStarted = true
        listeningStartMs = clock()
    }

    /** Primeira fala: inicia o relógio efetivo de escuta. */
    fun beginSpeech() {
        if (!started || speechStarted) return
        speechStarted = true
        listeningStartMs = clock()
        running = true
    }

    fun pause() {
        if (!running) return
        accumulatedMs += (clock() - listeningStartMs).coerceAtLeast(0L)
        running = false
    }

    fun resume() {
        if (running || !started || !speechStarted) return
        listeningStartMs = clock()
        running = true
    }

    fun speechSeconds(): Long {
        if (!speechStarted) return 0L
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
        speechStarted = false
    }
}
