package com.bettertalker.app.data.copilot

/**
 * MENTOR-CONTINUITY-P6 — recuperação segura do referente conversacional a
 * partir de mensagens já persistidas.
 *
 * Componente PURO: não lê Room, não toca o editor, não autoriza inserção e
 * não cria memória persistida. Reutiliza os contratos existentes de
 * `CardFirst` (`CardPayload.fromMap`, `resolveCardStatus`, `cardTargetKey`,
 * `supersededCardMessageIds`, `extractCardText`, `canInsertCard`) sem
 * alterá-los.
 *
 * Regra de segurança: o referente mais recente é o único candidato. Se ele
 * não tiver identidade e escopo comprovados — ou se o empate temporal for
 * ambíguo — não há referente seguro: nunca se escolhe silenciosamente um
 * card antigo.
 */
object ConversationalReferentRecovery {

    /** Projeção mínima de uma mensagem persistida (sem acoplamento a Room). */
    data class Message(
        val id: String,
        val noteId: String,
        /** null = conversa global (WHOLE); não nulo = conversa da seção. */
        val sectionId: String?,
        /** Payload já decodificado (ChatCodec.unescMap). */
        val payload: Map<String, String>,
        val createdAt: Long,
        val active: Boolean = true,
    )

    /** Estado do alvo informado pelo chamador — evita I/O no componente. */
    data class TargetState(val exists: Boolean, val currentHash: String? = null)

    data class Context(
        val noteId: String,
        /** null = WHOLE/global. */
        val sectionId: String? = null,
        /** Chave = `cardTargetKey(card)`. Ausência = alvo não comprovado. */
        val targets: Map<String, TargetState> = emptyMap(),
    ) {
        val whole: Boolean get() = sectionId == null
    }

    /**
     * Capacidades separadas — recuperar para conversa NÃO é autorizar
     * aprofundamento nem inserção.
     */
    data class Capabilities(
        val recoverableForConversation: Boolean,
        val deepenable: Boolean,
        val insertable: Boolean,
    )

    sealed interface Outcome {
        data class Found(
            val messageId: String,
            val card: CardPayload,
            val sectionId: String?,
            val status: CardStatus,
            /** Texto aplicável extraído do payload — contexto, nunca evidência. */
            val text: String?,
            val capabilities: Capabilities,
        ) : Outcome

        /** Empate temporal entre referentes distintos —fail-closed. */
        data object Ambiguous : Outcome

        /** Sem referente com identidade comprovada. */
        data object None : Outcome
    }

    /**
     * Seleciona o último referente recuperável. Ordenação determinística por
     * (createdAt, id); o mais recente é o único candidato — sem fallback para
     * cards antigos quando o recente é incompatível ou ambíguo.
     */
    fun recover(messages: List<Message>, context: Context): Outcome {
        data class Candidate(
            val message: Message,
            val card: CardPayload,
            val status: CardStatus,
        )

        val cardsInOrder = messages
            .filter { it.active }
            .mapNotNull { m -> CardPayload.fromMap(m.payload)?.let { m to it } }
            .sortedWith(compareBy({ it.first.createdAt }, { it.first.id }))

        if (cardsInOrder.isEmpty()) return Outcome.None
        val superseded = supersededCardMessageIds(cardsInOrder.map { it.first.id to it.second })

        fun eligible(pair: Pair<Message, CardPayload>): Candidate? {
            val (m, card) = pair
            if (m.noteId != context.noteId) return null
            if (context.whole) {
                if (m.sectionId != null) return null
                if (card.targetKind != CardTargetKind.NOTE) return null
            } else {
                if (m.sectionId != context.sectionId) return null
            }
            val key = cardTargetKey(card) ?: return null
            val target = context.targets[key] ?: return null
            val status = resolveCardStatus(
                payload = card,
                targetExists = target.exists,
                currentTargetHash = target.currentHash,
                superseded = m.id in superseded,
            )
            // APPLIED (anteriormente aceito) continua referente legítimo;
            // SUPERSEDED/DISMISSED/FAILED/NO_TARGET/TARGET_MISSING não.
            if (status !in setOf(CardStatus.AVAILABLE, CardStatus.STALE, CardStatus.APPLIED)) return null
            return Candidate(m, card, status)
        }

        val newest = cardsInOrder.last()
        val second = cardsInOrder.dropLast(1).lastOrNull()
        if (second != null && second.first.createdAt == newest.first.createdAt &&
            second.first.id != newest.first.id
        ) {
            return Outcome.Ambiguous
        }

        val candidate = eligible(newest) ?: return Outcome.None
        val text = newest.first.payload["text"].orEmpty()
        val hasBody = extractCardText(text) != null
        return Outcome.Found(
            messageId = candidate.message.id,
            card = candidate.card,
            sectionId = candidate.message.sectionId,
            status = candidate.status,
            text = extractCardText(text),
            capabilities = Capabilities(
                recoverableForConversation = true,
                // Um card aceito (APPLIED) ou com hash antigo (STALE) pode
                // continuar sendo explicado — desde que tenha texto íntegro.
                deepenable = hasBody && candidate.status != CardStatus.DISMISSED &&
                    candidate.status != CardStatus.FAILED,
                // Apenas informa o estado calculado pelo gate existente;
                // não concede autorização de inserção.
                insertable = canInsertCard(candidate.status),
            ),
        )
    }
}
