package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.library.GlobalLibrarySource

/** Turno mínimo para o prompt global (sem depender da UI). */
data class GlobalPromptTurn(
    val fromMe: Boolean,
    val text: String,
)

/**
 * Prompt do assistente global.
 *
 * Envia títulos recentes, fontes da Biblioteca sob demanda e uma janela
 * curta da conversa — nunca o dossiê/HTML de uma nota. O system prompt fica
 * em [GenerationPrompts.GLOBAL_ASSISTANT] (profile GLOBAL_ASSISTANT).
 * Puro/testável.
 */
object GlobalAssistantPrompt {

    /** Orçamento por fonte no prompt global; o reader não usa este limite. */
    private const val MAX_SOURCE_CHARS = 6_000

    fun build(
        userText: String,
        recentTitles: List<String> = emptyList(),
        sources: List<GlobalLibrarySource> = emptyList(),
        libraryNote: String? = null,
        history: List<GlobalPromptTurn> = emptyList(),
    ): String = buildString {
        if (sources.isNotEmpty()) {
            appendLine(
                "FONTES DA BIBLIOTECA (base factual; use só estas fontes e cite [Fonte N]):"
            )
            sources.take(4).forEach { source ->
                append("[Fonte ${source.index}] ")
                append(source.publicationTitle)
                source.unitLabel?.let { append(" · $it") }
                source.unitTitle?.let { append(" — $it") }
                source.locationLabel.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                if (source.training) append(" · ORIENTAÇÃO DE ORATÓRIA (não é doutrina)")
                appendLine()
                val full = (source.promptText ?: source.snippet).trim()
                val sent = if (full.length > MAX_SOURCE_CHARS) {
                    full.take(MAX_SOURCE_CHARS).trimEnd() + "…"
                } else {
                    full
                }
                appendLine("\"$sent\"")
                if (sent.length < full.length) {
                    appendLine(
                        "(fonte ${source.index} truncada: ${sent.length} de " +
                            "${full.length} caracteres; o reader mostra o texto completo)"
                    )
                }
                appendLine()
            }
            appendLine("REGRAS DAS FONTES:")
            appendLine("- Fatos só podem vir das fontes acima; cite [Fonte N] na frase.")
            appendLine("- Não invente página, unidade ou trecho; não diga que não tem acesso.")
            appendLine("- Se as fontes não sustentarem a resposta, diga o que faltou.")
            appendLine("- be/th são orientação de oratória, nunca prova doutrinal.")
            appendLine()
        }
        libraryNote?.takeIf { it.isNotBlank() }?.let {
            appendLine("NOTA DE BUSCA: $it")
            appendLine()
        }
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
