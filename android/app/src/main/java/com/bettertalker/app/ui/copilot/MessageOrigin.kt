package com.bettertalker.app.ui.copilot

/**
 * Origem de uma mensagem do assistente no chat.
 *
 * COPILOT (default): resposta do Copilot remoto (Groq/Gemini).
 * LOCAL: retrieval on-device, scaffolding determinístico ou IA local —
 * nunca prosa gerada remotamente. Histórico antigo (sem a chave no
 * payload) decodifica como COPILOT.
 */
enum class MessageOrigin { COPILOT, LOCAL }

/**
 * Lê a origem do mapa decodificado do payload. Ausente/inválida =
 * COPILOT (retrocompatível com histórico antigo).
 */
internal fun messageOriginOf(payload: Map<String, String>): MessageOrigin =
    payload["origin"]?.let { raw ->
        runCatching { MessageOrigin.valueOf(raw) }.getOrNull()
    } ?: MessageOrigin.COPILOT
