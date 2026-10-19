package com.bettertalker.app.data.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fases de progresso do streaming (usadas pelo DeepSeek: parcial/Generating/Done).
 *
 * Só estados verdadeiros, nunca porcentagem inventada:
 * - [LoadingModel]: engine LiteRT carregando (primeiro uso; ~5 s no A34).
 * - [ReadingSources]: RAG/recuperação + preparação do prompt em curso.
 * - [Generating]: tokens sendo gerados.
 * - [Done]: resposta concluída e postada.
 * - [Idle]: nenhum turno local em andamento.
 *
 * Compartilhado (object) porque o provider é instanciado por
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

    /**
     * Raciocínio interno (`reasoning_content`) da tentativa atual. Transitório
     * por decisão de produto: não persiste no payload da mensagem e é limpo a
     * cada tentativa. A UI pode exibi-lo enquanto a geração está em andamento.
     */
    private val _reasoningText = MutableStateFlow("")
    val reasoningText: StateFlow<String> = _reasoningText.asStateFlow()

    /**
     * Aviso honesto de nova tentativa (ex.: orçamento de tokens esgotado).
     * Não nulo só entre tentativas; o primeiro delta ou um erro o limpa.
     */
    private val _retryNotice = MutableStateFlow<String?>(null)
    val retryNotice: StateFlow<String?> = _retryNotice.asStateFlow()

    fun set(phase: LocalPhase) {
        _phase.value = phase
        // Qualquer transição real de fase encerra o aviso de retry.
        _retryNotice.value = null
    }

    fun appendPartial(chunk: String) {
        if (chunk.isNotEmpty()) {
            // Primeiro sinal real da nova tentativa: o aviso de retry cumpriu
            // seu papel e sai de cena.
            _retryNotice.value = null
            _partialText.value += chunk
        }
    }

    fun appendReasoning(chunk: String) {
        if (chunk.isNotEmpty()) {
            _retryNotice.value = null
            _reasoningText.value += chunk
        }
    }

    /**
     * Início de tentativa do provider: zera os parciais da tentativa anterior.
     * A partir da 2ª tentativa publica [retryNotice] para a UI explicar por que
     * o texto anterior sumiu, em vez de apagar em silêncio.
     */
    fun beginAttempt(attempt: Int, retryNotice: String?) {
        _partialText.value = ""
        _reasoningText.value = ""
        _retryNotice.value = if (attempt > 1) retryNotice else null
        _phase.value = LocalPhase.Idle
    }

    /** Volta ao repouso (início de cada geração local). */
    fun reset() {
        _partialText.value = ""
        _reasoningText.value = ""
        _retryNotice.value = null
        _phase.value = LocalPhase.Idle
    }

    /**
     * Encerra o streaming assim que o conteúdo final é persistido: o parcial
     * transitório não pode coexistir com a mensagem final (evita duplicação
     * visual e camada fantasma no LazyColumn).
     */
    fun clearPartial() {
        _partialText.value = ""
        _reasoningText.value = ""
        _retryNotice.value = null
    }
}
