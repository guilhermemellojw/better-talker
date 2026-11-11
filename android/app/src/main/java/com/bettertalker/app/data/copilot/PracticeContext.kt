package com.bettertalker.app.data.copilot

/**
 * Fatia 5 — contexto de prática (Treino/Palco) do assistente.
 *
 * O snapshot é somente leitura: readiness de texto, última preparação
 * ESCOLHIDA (não é resultado de treino) e a sessão salva do Palco. Nada aqui
 * persiste métricas de ensaio — o MVP de Treino continua stateless.
 */
enum class PracticePresentState { READY, PARTIAL, MISSING }

data class PracticeSnapshot(
    val noteId: String,
    val hasTrainingText: Boolean = false,
    /** Texto treinável por seção (S-34 conta subpontos; V&M conta contentHtml). */
    val hasTextBySectionId: Map<String, Boolean> = emptyMap(),
    val trainPrepSummary: String? = null,
    val presentState: PracticePresentState = PracticePresentState.MISSING,
    val presentLabel: String = "Falta gerar o mapa mental",
    val savedSectionIndex: Int? = null,
    val savedSectionTitle: String? = null,
    val savedElapsedSec: Long? = null,
) {
    val hasSavedSession: Boolean
        get() = savedSectionIndex != null && savedElapsedSec != null

    /** "continuar da seção 3 (Ponto 2) · 12:34" — null sem sessão salva. */
    val resumeLabel: String?
        get() {
            val index = savedSectionIndex ?: return null
            val elapsed = savedElapsedSec ?: return null
            val title = savedSectionTitle?.trim()?.takeIf { it.isNotBlank() }
            val suffix = if (title != null) " ($title)" else ""
            // Tempo antes do título: a linha da UI truncar não esconde o dado.
            return "continuar da seção ${index + 1} · " +
                "${formatPracticeElapsed(elapsed)}$suffix"
        }

    /** Linha compacta para a UI do Assistente. */
    val contextLine: String
        get() = buildString {
            append("Treino: ")
            append(if (hasTrainingText) "pronto" else "sem texto")
            trainPrepSummary?.let { append(" · preparação: $it") }
            append(" · Palco: ").append(presentLabel)
            resumeLabel?.let { append(" · ").append(it) }
        }

    /** Bloco do prompt: informa o próximo passo, sem inventar resultado. */
    val promptBlock: String
        get() = buildString {
            appendLine("## CONTEXTO DE PRÁTICA (snapshot; não é resultado de treino)")
            append("- Treino: ")
            append(if (hasTrainingText) "pronto para treinar" else "ainda sem texto treinável")
            append(
                trainPrepSummary?.let { "; última preparação escolhida: $it" }
                    ?: "; nenhuma preparação escolhida ainda"
            )
            appendLine(".")
            append("- Palco: ").append(presentLabel)
            resumeLabel?.let { append("; sessão salva: $it") }
            appendLine(".")
            append(
                "Use este snapshot apenas para orientar o próximo passo. " +
                    "Não invente métricas de treino e não altere a nota."
            )
        }.trim()
}

/** mm:ss (ou h:mm:ss) para o resumo da sessão salva. Puro/testável. */
fun formatPracticeElapsed(totalSeconds: Long): String {
    val sec = totalSeconds.coerceAtLeast(0L)
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}
