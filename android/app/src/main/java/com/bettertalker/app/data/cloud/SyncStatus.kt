package com.bettertalker.app.data.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F5g (Discovery #3) — estado VOLÁTIL do sync, para observabilidade.
 *
 * Sem persistência: o último sync bem-sucedido continua em
 * `SettingsStore.lastSync` (não duplicar). Vive no processo do app (o
 * WorkManager roda no processo principal), então a tela Conta e o
 * SyncWorker veem o mesmo objeto.
 */
object SyncStatus {
    sealed interface State {
        data object Idle : State
        data object Running : State
        data class Success(val time: Long) : State
        data class Error(val message: String, val time: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(s: State) { _state.value = s }
}

/**
 * F5g: texto puro do indicador da tela Conta. O caso `Idle` com
 * `lastSync > 0` (sincronizou antes de o app reiniciar) fica para a UI,
 * que combina com o valor persistido. Puro/testável.
 */
fun syncIndicatorText(state: SyncStatus.State, now: Long): String = when (state) {
    is SyncStatus.State.Idle -> "Nunca sincronizado"
    is SyncStatus.State.Running -> "Sincronizando…"
    is SyncStatus.State.Success -> "Sincronizado " + relativeAgo(now - state.time)
    is SyncStatus.State.Error -> "Erro no sync: ${state.message}"
}

private fun relativeAgo(elapsedMs: Long): String {
    val mins = (elapsedMs / 60_000).coerceAtLeast(0)
    return when {
        mins < 1 -> "agora mesmo"
        mins < 60 -> "há $mins min"
        mins < 60 * 24 -> "há ${mins / 60} h"
        else -> "há ${mins / (60 * 24)} dia(s)"
    }
}
