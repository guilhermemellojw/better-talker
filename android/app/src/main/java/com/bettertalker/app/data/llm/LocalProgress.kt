package com.bettertalker.app.data.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F2.1 — fases semânticas do provider on-device (Gemma local).
 *
 * Só estados verdadeiros, nunca porcentagem inventada:
 * - [LoadingModel]: engine LiteRT carregando (primeiro uso; ~5 s no A34).
 * - [ReadingSources]: RAG/recuperação + preparação do prompt em curso.
 * - [Generating]: tokens sendo gerados.
 * - [Done]: resposta concluída e postada.
 * - [Idle]: nenhum turno local em andamento.
 *
 * Compartilhado (object) porque o [LocalGemmaProvider] é instanciado por
 * chamada na [ProviderFactory]; a UI observa via ViewModel sem tocar o fluxo.
 */
enum class LocalPhase {
    Idle,
    LoadingModel,
    ReadingSources,
    Generating,
    Done,
}

object LocalProgress {
    private val _phase = MutableStateFlow(LocalPhase.Idle)
    val phase: StateFlow<LocalPhase> = _phase.asStateFlow()

    /** Texto parcial acumulado durante [LocalPhase.Generating] (só UI transitória). */
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    fun set(phase: LocalPhase) {
        _phase.value = phase
    }

    fun appendPartial(chunk: String) {
        if (chunk.isNotEmpty()) _partialText.value += chunk
    }

    /** Volta ao repouso (início de cada geração local). */
    fun reset() {
        _partialText.value = ""
        _phase.value = LocalPhase.Idle
    }
}
