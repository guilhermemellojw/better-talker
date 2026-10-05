package com.bettertalker.app.ui.aimodel

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.ai.LlmConfig
import com.bettertalker.app.data.ai.LlmModelConfig
import com.bettertalker.app.data.ai.ModelDlState
import com.bettertalker.app.data.ai.ModelDownloadManager
import com.bettertalker.app.data.ai.downloadProgress
import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.llm.DeepSeekProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ProviderError
import com.bettertalker.app.data.llm.litert.LitertGemmaEngine
import com.bettertalker.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelViewModel(ctx: android.content.Context) : ViewModel() {
    private val app = ctx.applicationContext
    private val dl = ModelDownloadManager(app)
    private val settings = SettingsStore(app)
    val state = dl.state

    private val _totalRam = MutableStateFlow(0L)
    val totalRam = _totalRam.asStateFlow()

    /** Chave BYOD do Gemini (Fase 18): remota sob demanda, local sempre. */
    val llmApiKey = settings.llmApiKey.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), "")

    /** F20-F1: chave BYOD do Groq/Qwen + provider selecionado. */
    val groqApiKey = settings.groqApiKey.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), "")
    /** T3: default "auto" (local → remoto → determinístico); explícitos preservados. */
    val llmProvider = settings.llmProvider.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), "auto")

    /** T4 — chave BYOD do DeepSeek + modelo escolhido (flash | v4-pro). */
    val deepseekApiKey = settings.deepseekApiKey.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), "")
    val deepseekModel = settings.deepseekModel.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), "deepseek-flash")

    /** T4 — resultado da última sonda: null|"checking"|"online"|"auth"|"error". */
    private val _deepseekProbe = MutableStateFlow<String?>(null)
    val deepseekProbe = _deepseekProbe.asStateFlow()

    fun saveApiKey(v: String) = viewModelScope.launch { settings.setLlmApiKey(v) }
    fun clearApiKey() = viewModelScope.launch { settings.clearLlmApiKey() }
    fun saveGroqApiKey(v: String) = viewModelScope.launch { settings.setGroqApiKey(v) }
    fun clearGroqApiKey() = viewModelScope.launch { settings.clearGroqApiKey() }
    fun selectProvider(v: String) = viewModelScope.launch { settings.setLlmProvider(v) }

    /** T4 — chave BYOD do DeepSeek; trocar chave/modelo invalida a sonda. */
    fun saveDeepseekApiKey(v: String) = viewModelScope.launch {
        settings.setDeepseekApiKey(v)
        _deepseekProbe.value = null
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
                    message = "ping",
                    maxOutputTokens = 1,
                    maxAttempts = 1,
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

    /** T3 — modelo presente (download concluído ou `adb push` em dev). */
    fun isModelPresent(): Boolean = LitertGemmaEngine.isModelPresent(app)

    init {
        viewModelScope.launch {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            _totalRam.value = info.totalMem
        }
    }

    fun ramOk(): Boolean = LlmConfig.ramOk(_totalRam.value)

    fun progressOf(s: ModelDlState): Float = when (s) {
        is ModelDlState.Downloading -> downloadProgress(s.doneBytes, s.totalBytes)
        is ModelDlState.Ready -> 1f
        else -> 0f
    }

    fun start() {
        if (!LlmModelConfig.configured()) return
        dl.start(viewModelScope, LlmModelConfig.DOWNLOAD_URL, LlmModelConfig.SHA256,
            LlmModelConfig.EXPECTED_BYTES)
    }

    fun cancel() = dl.cancel()
    fun delete() = viewModelScope.launch { dl.delete() }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ModelViewModel(ctx) as T
    }
}

/** T4 — estado exibido do card DeepSeek. Puro/testável. */
enum class DeepSeekUiStatus { NO_KEY, CONFIGURED, CHECKING, ONLINE, AUTH_ERROR, ERROR }

fun deepSeekUiStatus(apiKey: String, probe: String?): DeepSeekUiStatus = when {
    apiKey.isBlank() -> DeepSeekUiStatus.NO_KEY
    probe == null -> DeepSeekUiStatus.CONFIGURED
    probe == "checking" -> DeepSeekUiStatus.CHECKING
    probe == "online" -> DeepSeekUiStatus.ONLINE
    probe == "auth" -> DeepSeekUiStatus.AUTH_ERROR
    else -> DeepSeekUiStatus.ERROR
}
