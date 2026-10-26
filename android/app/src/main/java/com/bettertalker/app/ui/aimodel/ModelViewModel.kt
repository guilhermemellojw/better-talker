package com.bettertalker.app.ui.aimodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.llm.DeepSeekProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ProviderError
import com.bettertalker.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Tela de IA — APENAS DeepSeek BYOD (cleanup multi-LLM). Sem chave, o app
 * segue no motor determinístico; nenhum provider é embutido.
 */
class ModelViewModel(ctx: android.content.Context) : ViewModel() {
    private val app = ctx.applicationContext
    private val settings = SettingsStore(app)

    /** T4 — chave BYOD do DeepSeek + modelo escolhido (flash | v4-pro). */
    val deepseekApiKey = settings.deepseekApiKey.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), "")
    val deepseekModel = settings.deepseekModel.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), "deepseek-flash")

    /** T4 — resultado da última sonda: null|"checking"|"online"|"auth"|"error". */
    private val _deepseekProbe = MutableStateFlow<String?>(null)
    val deepseekProbe = _deepseekProbe.asStateFlow()

    /** T4 — chave BYOD do DeepSeek; trocar chave/modelo invalida a sonda. */
    fun saveDeepseekApiKey(v: String) = viewModelScope.launch {
        try {
            settings.setDeepseekApiKey(v)
            _deepseekProbe.value = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keystore indisponível/invalidado: nunca cair para texto claro.
            _deepseekProbe.value = "save-error"
        }
    }
    fun clearDeepseekApiKey() = viewModelScope.launch {
        settings.clearDeepseekApiKey()
        _deepseekProbe.value = null
    }
    fun selectDeepseekModel(v: String) = viewModelScope.launch {
        settings.setDeepseekModel(v)
        _deepseekProbe.value = null
    }

    /** T4 — sonda mínima de autenticação (1 token) para o indicador de status. */
    fun checkDeepSeek() = viewModelScope.launch {
        val key = settings.deepseekApiKey.first()
        if (key.isBlank()) {
            _deepseekProbe.value = null
            return@launch
        }
        _deepseekProbe.value = "checking"
        val model = settings.deepseekModel.first()
        try {
            DeepSeekProvider(apiKey = key, model = model).generate(
                LlmRequest(
                    text = "",
                    message = "Responda apenas: OK",
                    // T2 — orçamento suficiente para o thinking + o content;
                    // 2 tentativas porque o provider refaz uma vez se o
                    // reasoning esvaziar a resposta (finish_reason=length).
                    maxOutputTokens = 64,
                    maxAttempts = 2,
                    timeoutMs = 10_000,
                )
            )
            _deepseekProbe.value = "online"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ProviderError) {
            _deepseekProbe.value =
                if (e.code == ProviderErrorCode.AUTHENTICATION) "auth" else "error"
        } catch (_: Exception) {
            _deepseekProbe.value = "error"
        }
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ModelViewModel(ctx) as T
    }
}

/** T4 — estado exibido do card DeepSeek. Puro/testável. */
enum class DeepSeekUiStatus { NO_KEY, CONFIGURED, CHECKING, ONLINE, AUTH_ERROR, SAVE_ERROR, ERROR }

fun deepSeekUiStatus(apiKey: String, probe: String?): DeepSeekUiStatus = when {
    probe == "save-error" -> DeepSeekUiStatus.SAVE_ERROR
    apiKey.isBlank() -> DeepSeekUiStatus.NO_KEY
    probe == null -> DeepSeekUiStatus.CONFIGURED
    probe == "checking" -> DeepSeekUiStatus.CHECKING
    probe == "online" -> DeepSeekUiStatus.ONLINE
    probe == "auth" -> DeepSeekUiStatus.AUTH_ERROR
    else -> DeepSeekUiStatus.ERROR
}
