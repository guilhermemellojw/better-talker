package com.bettertalker.app.data.copilot

/**
 * BETA-TRUST/P1 — contrato central de elegibilidade do conteúdo gerado pela IA.
 *
 * Uma única decisão é reutilizada por TODAS as rotas de utilização: resposta
 * final, histórico/reabertura, cópia de resposta, cópia de card, inserção de
 * card, inserção no tópico/editor, conteúdo parcial cancelado, rascunho e
 * revisão. Nenhuma rota pode aceitar texto por aviso visual ou por status de
 * card sem esta decisão.
 */
data class ContentEligibility(
    val allowed: Boolean,
    val text: String,
    val notice: String?,
    val findings: List<FidelityFinding>,
    val removed: List<String>,
) {
    val blocked: Boolean get() = !allowed
}

object ContentEligibilityGate {

    /** Decisão canônica: proveniência é always-on, nunca opt-in. */
    fun decide(
        text: String,
        corpus: List<String>,
        questionRefs: List<String> = emptyList(),
    ): ContentEligibility {
        val fidelity = SourceFidelityGate.enforce(text, corpus, questionRefs)
        return ContentEligibility(
            allowed = !fidelity.blocked && fidelity.text.isNotBlank(),
            text = fidelity.text,
            notice = fidelity.notice,
            findings = fidelity.findings,
            removed = fidelity.removed,
        )
    }

    /** Texto seguro para qualquer rota de leitura/cópia/inserção. */
    fun safeText(
        text: String,
        corpus: List<String>,
        questionRefs: List<String> = emptyList(),
    ): String = decide(text, corpus, questionRefs).text

    fun forFinalAnswer(text: String, corpus: List<String>, refs: List<String>) =
        decide(text, corpus, refs)

    fun forHistory(text: String, corpus: List<String>, refs: List<String>) =
        decide(text, corpus, refs)

    fun forResponseCopy(text: String, corpus: List<String>, refs: List<String>) =
        decide(text, corpus, refs)

    fun forCardCopy(text: String, corpus: List<String>, refs: List<String>) =
        decide(text, corpus, refs)

    fun forCardInsert(text: String, corpus: List<String>, refs: List<String>) =
        decide(text, corpus, refs)

    fun forTopicInsert(text: String, corpus: List<String>, refs: List<String>) =
        decide(text, corpus, refs)

    fun forCancelledPartial(text: String, corpus: List<String>, refs: List<String>) =
        decide(text, corpus, refs)

    fun forDraftReview(text: String, corpus: List<String>, refs: List<String>) =
        decide(text, corpus, refs)
}
