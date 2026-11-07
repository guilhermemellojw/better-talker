package com.bettertalker.app.data.copilot

/** Turno mínimo para o prompt global (sem depender da UI). */
data class GlobalPromptTurn(
    val fromMe: Boolean,
    val text: String,
)

/**
 * Prompt do assistente global.
 *
 * Envia só os TÍTULOS recentes e uma janela curta da conversa — nunca o
 * dossiê/HTML de uma nota. O system prompt fica em
 * [GenerationPrompts.GLOBAL_ASSISTANT] (profile GLOBAL_ASSISTANT).
 * Puro/testável.
 */
object GlobalAssistantPrompt {

    fun build(
        userText: String,
        recentTitles: List<String> = emptyList(),
        history: List<GlobalPromptTurn> = emptyList(),
    ): String = buildString {
        if (recentTitles.isNotEmpty()) {
            appendLine("TÍTULOS RECENTES (só títulos; você não leu o conteúdo):")
            recentTitles.take(6).forEach { appendLine("- $it") }
            appendLine()
        }
        val window = history.takeLast(6).filter { it.text.isNotBlank() }
        if (window.isNotEmpty()) {
            appendLine("CONVERSA RECENTE:")
            window.forEach { turn ->
                appendLine(if (turn.fromMe) "Usuário: ${turn.text}" else "BT: ${turn.text}")
            }
            appendLine()
        }
        appendLine("PEDIDO ATUAL:")
        append(userText.trim())
    }
}
