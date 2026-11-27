package com.bettertalker.app.data.copilot

/**
 * BETA-PEDAGOGY/P4.1 — decisão de handoff da revisão AI-09.
 *
 * Esta é a decisão executada por `CopilotViewModel.post`: quando a chamada
 * declara fonte de revisão, a proveniência da mensagem revisada é derivada
 * EXCLUSIVAMENTE da fonte persistida. Falta de fonte ou de texto-fonte, nota
 * divergente ou vínculo inválido => falha fechada, sem gravar mensagem/card e
 * sem usar o corpus do turno corrente como fallback.
 *
 * C-owned: nenhuma regra de fidelidade é reimplementada aqui; o contrato de A
 * (`DeliveryProvenance.rewriteFrom`) continua sendo a única autoridade.
 */
object RewriteHandoff {

    data class Input(
        val fromMe: Boolean,
        val source: DeliveryProvenance?,
        val sourceText: String?,
        val revisedText: String,
        val messageId: String,
        val turnId: String?,
        val noteId: String?,
        val scopeId: String?,
        val targetKey: String?,
        val boundAt: Long,
        /** Corpus do turno corrente — registrado apenas para auditoria. */
        val currentTurnCorpus: List<String> = emptyList(),
    )

    sealed interface Outcome {
        data class Accepted(val provenance: DeliveryProvenance) : Outcome
        data class Rejected(val reason: String) : Outcome
        /** Chamada comum (não revisão): segue o fluxo normal de geração. */
        data object NotApplicable : Outcome
    }

    fun decide(input: Input): Outcome {
        if (input.fromMe) return Outcome.NotApplicable
        if (input.source == null && input.sourceText == null) return Outcome.NotApplicable
        if (input.source == null) return Outcome.Rejected("SOURCE_MISSING")
        if (input.sourceText.isNullOrBlank()) return Outcome.Rejected("SOURCE_TEXT_MISSING")
        val note = input.noteId ?: return Outcome.Rejected("NOTE_MISSING")
        val provenance = DeliveryProvenance.rewriteFrom(
            source = input.source,
            sourceText = input.sourceText,
            revisedText = input.revisedText,
            messageId = input.messageId,
            turnId = input.turnId,
            noteId = note,
            scopeId = input.scopeId,
            targetKey = input.targetKey,
            boundAt = input.boundAt,
        ) ?: return Outcome.Rejected("REWRITE_INVALID")
        return Outcome.Accepted(provenance)
    }
}
