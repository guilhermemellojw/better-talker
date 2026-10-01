package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ProviderErrorCode
import kotlinx.coroutines.CancellationException

/**
 * Orquestra múltiplos [LlmProvider] em ordem de prioridade.
 *
 * Tenta o primeiro; se falhar em condição "recuperável", tenta o próximo.
 * CancellationException sempre propaga imediatamente (structured concurrency).
 *
 * Falhas que disparam fallback:
 *   RATE_LIMIT, AUTHENTICATION, UNAVAILABLE, TIMEOUT, NETWORK, Exception genérica,
 *   resposta em branco (text.isBlank() && !meta.offline)
 * Falhas que NÃO disparam:
 *   INVALID_REQUEST, INVALID_RESPONSE (propagam — não é problema do provider)
 *
 * Resposta com meta.offline == true é SUCESSO TERMINAL — não tenta próximo.
 *
 * Circuit breaker: pula provider após falhas consecutivas, por janela de tempo.
 * Se TODOS estiverem bloqueados, ignora o breaker e tenta o primeiro mesmo assim.
 *
 * O [LlmResponseMeta.providerId] reflete o provider que efetivamente respondeu.
 */
class FallbackLlmProvider internal constructor(
    private val providers: List<LlmProvider>,
    private val circuitBreaker: CircuitBreaker,
) : LlmProvider {

    /** Uso normal: breaker default. (O primário é internal porque [CircuitBreaker] é internal.) */
    constructor(providers: List<LlmProvider>) : this(providers, CircuitBreaker())

    override val id: String = "fallback"
    override val model: String = "fallback"

    override suspend fun generate(request: LlmRequest): LlmResponse {
        if (providers.isEmpty()) {
            throw ProviderError(
                code = ProviderErrorCode.UNAVAILABLE,
                message = "Nenhum provider configurado.",
                providerId = id,
                attempts = 0,
            )
        }

        val candidates = selectCandidates()
        var lastError: ProviderError? = null

        for (provider in candidates) {
            try {
                val response = provider.generate(request)
                if (response.meta.offline) {
                    circuitBreaker.recordSuccess(provider.id)
                    return response
                }
                if (response.text.isBlank()) {
                    circuitBreaker.recordFailure(provider.id)
                    lastError = ProviderError(
                        code = ProviderErrorCode.INVALID_RESPONSE,
                        message = "Resposta vazia de ${provider.id}.",
                        providerId = provider.id,
                        attempts = response.meta.attempts,
                    )
                    continue
                }
                circuitBreaker.recordSuccess(provider.id)
                return response
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderError) {
                when (e.code) {
                    ProviderErrorCode.INVALID_REQUEST,
                    ProviderErrorCode.INVALID_RESPONSE -> throw e
                    else -> {
                        circuitBreaker.recordFailure(provider.id)
                        lastError = e
                    }
                }
            } catch (e: Exception) {
                circuitBreaker.recordFailure(provider.id)
                lastError = ProviderError(
                    code = ProviderErrorCode.NETWORK,
                    message = e.message ?: "Erro desconhecido em ${provider.id}.",
                    providerId = provider.id,
                    attempts = 0,
                )
            }
        }

        throw lastError ?: ProviderError(
            code = ProviderErrorCode.UNAVAILABLE,
            message = "Todos os providers falharam.",
            providerId = id,
            attempts = 0,
        )
    }

    private suspend fun selectCandidates(): List<LlmProvider> {
        val available = providers.filterNot { circuitBreaker.shouldSkip(it.id) }
        return if (available.isEmpty()) providers else available
    }
}
