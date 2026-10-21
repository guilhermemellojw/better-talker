package com.bettertalker.app.data.copilot

/** Teto do system prompt no arquivo exportado. O dossiê é o primeiro a ser cortado. */
const val MAX_EXPORT_SYSTEM_PROMPT_CHARS = 10_000

/** Uma mensagem de texto do escopo exportado. */
data class ChatExportMessage(
    val fromMe: Boolean,
    val text: String,
    /** HH:mm, já formatado para o fuso do aparelho. */
    val timeLabel: String,
)

/** Resultado do export pronto para compartilhar. Não é persistido. */
data class ChatExportResult(
    val markdown: String,
    val messageCount: Int,
    val systemPromptChars: Int,
    val dossierTruncated: Boolean,
)

/** System prompt final + se ele precisou ser cortado. */
data class CappedSystemPrompt(
    val text: String,
    val truncated: Boolean,
)

/**
 * Limita o system prompt exportado. Se o dossiê for o excedente, reconstrói o
 * prompt com o dossiê truncado, preservando persona/regras/cauda. Se nem sem o
 * dossiê couber, corta o system e avisa. Puro/testável.
 */
internal fun capExportSystemPrompt(
    system: String,
    dossierBlock: String?,
    maxChars: Int = MAX_EXPORT_SYSTEM_PROMPT_CHARS,
    rebuild: (String?) -> String,
): CappedSystemPrompt {
    if (system.length <= maxChars) return CappedSystemPrompt(system, false)

    val dossierMarker = "\n\n[ Dossiê truncado no export ]"
    val block = dossierBlock?.takeIf { it.isNotBlank() }
    if (block != null) {
        val withoutDossier = rebuild(null)
        val budget = maxChars - withoutDossier.length - dossierMarker.length - 80
        if (budget > 0) {
            val truncatedDossier = block.take(budget).trimEnd() + dossierMarker
            val rebuilt = rebuild(truncatedDossier)
            if (rebuilt.length <= maxChars) {
                return CappedSystemPrompt(rebuilt, true)
            }
        }
    }

    val omitted = (system.length - maxChars).coerceAtLeast(0)
    val truncated = system.take(maxChars).trimEnd() +
        "\n\n[ System prompt truncado no export — $omitted caracteres omitidos ]"
    return CappedSystemPrompt(truncated, true)
}

/**
 * Markdown único para o A/B: system prompt reconstruído + conversa da seção.
 * Puro/testável.
 */
fun buildChatExportMarkdown(
    noteTitle: String,
    sectionTitle: String?,
    exportedAt: String,
    systemPrompt: String,
    dossierTruncated: Boolean,
    messages: List<ChatExportMessage>,
): String = buildString {
    append("# Conversa — ")
    append(noteTitle.ifBlank { "Sem título" })
    append(" / ")
    append(sectionTitle?.takeIf { it.isNotBlank() } ?: "Discurso inteiro")
    append("\n\nExportado em: ").append(exportedAt).append("\n\n")
    append("## System prompt (injetado antes da conversa)\n\n")
    append("````text\n")
    append(systemPrompt.trim())
    append("\n````\n")
    if (dossierTruncated) append("\n_Dossiê truncado no export._\n")
    append("\n## Conversa\n\n")
    if (messages.isEmpty()) {
        append("_Nenhuma mensagem nesta seção._\n")
    } else {
        messages.forEachIndexed { index, message ->
            if (index > 0) append("\n")
            append("**").append(if (message.fromMe) "Você" else "Copilot").append("** (")
            append(message.timeLabel).append("):\n")
            append(message.text.trim()).append("\n")
        }
    }
}
