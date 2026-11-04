package com.bettertalker.app.data.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Build público: sync não existe, então não há Firebase nem login.
 *
 * A API espelha a implementação do flavor `syncBeta` para que a UI e os
 * repositórios compilem sem condicionais espalhadas. `isConfigured()` é
 * sempre falso e nenhum worker é agendado.
 */
class AuthRepository(@Suppress("UNUSED_PARAMETER") private val ctx: Context) {

    private val _user = MutableStateFlow<CloudUser?>(null)
    val user = _user.asStateFlow()

    private val _accountConflict = MutableStateFlow<String?>(null)
    val accountConflict = _accountConflict.asStateFlow()

    fun isConfigured(): Boolean = false

    fun start() = Unit

    fun stop() = Unit

    fun currentUser(): CloudUser? = null

    suspend fun signIn(uiContext: Context = ctx): String =
        "Sincronização em nuvem não está disponível neste build."

    fun signOut() = Unit

    fun clearAccountConflict() {
        _accountConflict.value = null
    }

    /** Limpeza local explícita: no público não há vínculo de conta. */
    fun clearLastUidBinding() = Unit

    suspend fun wipeCloud(): String? =
        "Sincronização em nuvem não está disponível neste build."
}
