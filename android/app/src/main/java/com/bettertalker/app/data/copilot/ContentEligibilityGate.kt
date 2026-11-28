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
        // PROBE_OPEN: aspas abertas/referência truncada também são bloqueadas
        // no resultado final, histórico e cards — não só no parcial.
        val safe = SourceFidelityGate.redactUnfinished(fidelity.text)
        val unfinished = safe != fidelity.text
        val findings = fidelity.findings + if (unfinished) {
            listOf(FidelityFinding.UNFINISHED_REDACTED)
        } else {
            emptyList()
        }
        val reasons = buildList {
            fidelity.notice?.removePrefix("⚠️ ")?.let { add(it) }
            if (unfinished) add("trecho incompleto removido; a resposta não foi entregue")
        }
        return ContentEligibility(
            // P1-B: fragmento rejeitado nunca é elegível, mesmo que sobreviva
            // texto à esquerda. A exibição segura existe apenas para parcial.
            allowed = !fidelity.blocked && !unfinished && safe.isNotBlank(),
            text = safe,
            notice = reasons.takeIf { it.isNotEmpty() }
                ?.joinToString("; ")
                ?.let { "⚠️ Revise: $it." },
            findings = findings,
            removed = fidelity.removed + if (unfinished) {
                listOf("trecho incompleto removido")
            } else {
                emptyList()
            },
        )
    }

    /** Texto seguro para leitura/cópia/inserção; vazio quando não elegível. */
    fun safeText(
        text: String,
        corpus: List<String>,
        questionRefs: List<String> = emptyList(),
    ): String {
        val decision = decide(text, corpus, questionRefs)
        return if (decision.allowed) decision.text else ""
    }

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
