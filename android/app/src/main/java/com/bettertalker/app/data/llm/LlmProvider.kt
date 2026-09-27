package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.domain.ContextPack

/**
 * Fase 18 — BLOCO A. Contrato do provider LLM nativo.
 *
 * Equivalente conceitual ao `LlmProvider` do web (Fase 4): a UI/ViewModel
 * conversa com esta abstração, nunca com Gemini direto. Permite trocar o
 * remoto, o on-device ou fakes sem tocar no pipeline do chat.
 *
 * Puro/testável: sem Android, sem IO nesta interface (a IO vive nas
 * implementações, atrás de [LlmHttpClient] injetável).
 */

/** Ação do Copilot. 'chat' = chat livre, roteado internamente por intent. */
enum class LlmAction { HOOK, REWRITE, CRITIQUE, CUES, SHORTEN, CHAT }

enum class LlmTone { TED, PITCH, MOTIVATIONAL, ACADEMIC, HUMOROUS }

data class LlmRequest(
    /** Texto do bloco em foco (ou seleção — ver §16). */
    val text: String,
    val action: LlmAction = LlmAction.CHAT,
    /** Mensagem literal do usuário no chat livre. */
    val message: String = "",
    /** Histórico anterior (janela 6/500 — nunca o banco inteiro). */
    val history: List<com.bettertalker.app.data.copilot.ChatTurn> = emptyList(),
    val isFirstMessage: Boolean = true,
    val tone: LlmTone = LlmTone.TED,
    val contextPack: ContextPack? = null,
    /** Legado: trechos planos. Preferir contextPack. */
    val contextPassages: List<String> = emptyList(),
    val blockTitle: String? = null,
    val blockMinutes: Int? = null,
    val timeoutMs: Long = DEFAULT_LLM_TIMEOUT_MS,
    val maxAttempts: Int = DEFAULT_LLM_MAX_ATTEMPTS,
    /**
     * Fase 5 (BLOCO B): 'edit-proposal' pede JSON em cerca para virar
     * proposta (parse + validação locais). Default 'text'.
     */
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,
    val editMode: com.bettertalker.app.data.edit.EditProposalMode? = null,
    /** Foco vindo do chat (mensagem do usuário) para a proposta. */
    val brief: String = ""
)

enum class ResponseFormat { TEXT, EDIT_PROPOSAL }

data class LlmResponseMeta(
    val providerId: String,
    val model: String,
    val durationMs: Long,
    val attempts: Int,
    /** true = motor offline determinístico (sem chave). */
    val offline: Boolean
)

data class LlmResponse(val text: String, val meta: LlmResponseMeta)

/**
 * Erro estruturado do provider. A mensagem é segura para log (nunca contém
 * a chave); a UI usa `friendlyChatError(code)` para o texto humano.
 */
class ProviderError(
    val code: ProviderErrorCode,
    message: String,
    val providerId: String,
    val attempts: Int
) : Exception(message)

interface LlmProvider {
    val id: String
    val model: String
    suspend fun generate(request: LlmRequest): LlmResponse
}

/** Padrões centralizados, mesmos valores do web (§15 F15). */
const val DEFAULT_LLM_TIMEOUT_MS: Long = 30_000L
const val DEFAULT_LLM_MAX_ATTEMPTS: Int = 2

/** Backoff entre tentativas transitórias: 400ms × tentativa (mesmo do web). */
const val RETRY_BACKOFF_MS: Long = 400L

/**
 * Transporte HTTP injetável: a implementação real usa HttpURLConnection;
 * os testes usam fake (sem rede na JVM).
 */
interface LlmHttpClient {
    data class HttpResult(val status: Int, val body: String)

    @Throws(Exception::class)
    suspend fun postJson(url: String, body: String, timeoutMs: Long): HttpResult
}
