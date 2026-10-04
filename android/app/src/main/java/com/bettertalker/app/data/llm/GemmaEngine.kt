package com.bettertalker.app.data.llm

import kotlinx.coroutines.flow.Flow

/**
 * F2.1 — contrato mínimo do motor on-device, para o provider não depender da
 * implementação LiteRT direta (testes JVM injetam fake; sem Robolectric).
 */
interface GemmaEngine {
    /** Garante o modelo carregado (no-op se já está; fora da main thread). */
    suspend fun warmup()

    /** Gera texto com contexto limpo (conversa nova por chamada). */
    fun generate(system: String, user: String, maxOutputTokens: Int): Flow<String>
}
