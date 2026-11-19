package com.bettertalker.app.data.copilot

/**
 * 1C.2.1B2A — vocabulário controlado de assuntos de decisão.
 *
 * A chave persistida (`key`) é estável. Renomear uma chave existente exigiria
 * migração de dados e não é permitido silenciosamente. Chaves desconhecidas
 * são tratadas como ambíguas pelo classificador: nunca viram curinga.
 *
 * O escopo faz parte do contrato: uma decisão de seção não deve usar assunto
 * de nota, e vice-versa.
 */
enum class DecisionSubject(
    val key: String,
    val scope: DecisionScope,
) {
    SECTION_APPROACH("section.approach", DecisionScope.SECTION),
    SECTION_OPENING("section.opening", DecisionScope.SECTION),
    SECTION_ILLUSTRATION("section.illustration", DecisionScope.SECTION),
    SECTION_APPLICATION("section.application", DecisionScope.SECTION),
    SECTION_CONCLUSION("section.conclusion", DecisionScope.SECTION),
    SECTION_STRUCTURE("section.structure", DecisionScope.SECTION),
    NOTE_TONE("note.tone", DecisionScope.NOTE),
    NOTE_STRUCTURE("note.structure", DecisionScope.NOTE),
    ;

    companion object {
        /**
         * Exato e case-sensitive. `null`/desconhecido retorna `null` de
         * propósito: o chamador deve tratar como assunto ambíguo, nunca como
         * "qualquer assunto".
         */
        fun fromKey(key: String?): DecisionSubject? =
            key?.let { value -> entries.firstOrNull { it.key == value } }
    }
}

/** Resultado puro da comparação entre uma decisão nova e o histórico. */
sealed interface DecisionRelationship {

    /** Mesmo evento determinístico; preserva a idempotência da B1. */
    data class SameEvent(val existing: DecisionRecord) : DecisionRelationship

    /** Notas, escopos, seções ou assuntos diferentes; coexistência. */
    data object Independent : DecisionRelationship

    /** Candidato único com o mesmo assunto conhecido. */
    data class PotentialOverlap(val candidate: DecisionRecord) : DecisionRelationship

    /**
     * Candidato único por fallback legado: ambas são `STRUCTURE_ACCEPT` do
     * escopo `SECTION`, da mesma nota/seção original, e pelo menos uma não
     * tem `subjectKey`.
     */
    data class LegacyPotentialOverlap(val candidate: DecisionRecord) : DecisionRelationship

    /**
     * Não há candidato único seguro. O chamador não deve escolher
     * automaticamente; uma futura confirmação explícita decide.
     */
    data class Ambiguous(
        val candidates: List<DecisionRecord>,
        val reason: AmbiguityReason,
    ) : DecisionRelationship

    /** A decisão de entrada não participa da política inicial. */
    data class Ineligible(val reason: IneligibleReason) : DecisionRelationship
}

enum class AmbiguityReason {
    /** Mais de um candidato simultâneo. */
    MULTIPLE_CANDIDATES,

    /** Mesma chave desconhecida; não há como validar o assunto. */
    UNKNOWN_SUBJECT,

    /** Mesmo id com nota/escopo/seção/proveniência/timestamp divergentes. */
    IDENTITY_COLLISION,
}

enum class IneligibleReason {
    NOT_ACTIVE,
    NOT_CONFIRMED,
    SECTION_LINK_NOT_VALID,
    SUBJECT_SCOPE_MISMATCH,
}

/**
 * 1C.2.1B2A — classificação conservadora de relacionamento entre decisões.
 *
 * Regras:
 * - mesmo id/identidade → [DecisionRelationship.SameEvent] (prioridade máxima,
 *   preservando a idempotência da B1);
 * - só entram na política inicial decisões `ACTIVE`, `CONFIRMED` e com vínculo
 *   válido (`VALID` para SECTION, `NOT_APPLICABLE` para NOTE);
 * - a comparação é por `noteId`, escopo, seção atual e assunto; nunca por
 *   similaridade de texto ou LLM;
 * - `subjectKey` igual e conhecido → candidato; chaves diferentes → sem
 *   relação; chave desconhecida igual → ambíguo;
 * - decisões legadas sem `subjectKey` só são candidatas quando AMBAS são
 *   `STRUCTURE_ACCEPT`, do escopo `SECTION`, da mesma nota/seção;
 * - mais de um candidato → [DecisionRelationship.Ambiguous], nunca escolha
 *   automática.
 *
 * A função é pura, determinística e NÃO executa transições. Ela apenas prepara
 * uma futura confirmação explícita (B2B/B2C).
 */
fun classifyDecisionRelationship(
    incoming: DecisionRecord,
    existing: List<DecisionRecord>,
): DecisionRelationship {
    val duplicateIds = existing.groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
    if (duplicateIds.isNotEmpty()) {
        return DecisionRelationship.Ambiguous(
            candidates = existing.filter { it.id in duplicateIds },
            reason = AmbiguityReason.IDENTITY_COLLISION,
        )
    }

    val sameId = existing.filter { it.id == incoming.id }
    if (sameId.isNotEmpty()) {
        val match = sameId.single()
        return if (hasSameEventIdentity(incoming, match)) {
            DecisionRelationship.SameEvent(match)
        } else {
            DecisionRelationship.Ambiguous(
                candidates = listOf(match),
                reason = AmbiguityReason.IDENTITY_COLLISION,
            )
        }
    }

    ineligibleReason(incoming)?.let {
        return DecisionRelationship.Ineligible(it)
    }

    val known = mutableListOf<DecisionRecord>()
    val legacy = mutableListOf<DecisionRecord>()
    val unknown = mutableListOf<DecisionRecord>()

    existing.forEach { candidate ->
        if (candidate.id == incoming.id) return@forEach
        if (ineligibleReason(candidate) != null) return@forEach
        if (!sameDecisionLocation(incoming, candidate)) return@forEach

        when (subjectMatch(incoming, candidate)) {
            SubjectMatch.NONE -> Unit
            SubjectMatch.KNOWN -> known += candidate
            SubjectMatch.LEGACY -> legacy += candidate
            SubjectMatch.UNKNOWN -> unknown += candidate
        }
    }

    val all = known + legacy + unknown
    return when {
        all.isEmpty() -> DecisionRelationship.Independent
        all.size > 1 -> DecisionRelationship.Ambiguous(
            candidates = all,
            reason = AmbiguityReason.MULTIPLE_CANDIDATES,
        )
        known.size == 1 -> DecisionRelationship.PotentialOverlap(known.single())
        legacy.size == 1 -> DecisionRelationship.LegacyPotentialOverlap(legacy.single())
        else -> DecisionRelationship.Ambiguous(
            candidates = all,
            reason = AmbiguityReason.UNKNOWN_SUBJECT,
        )
    }
}

/**
 * Identidade de ORIGEM do evento, não do estado atual.
 *
 * `sectionId` pode mudar após remapeamento confirmado e `createdAt` pode
 * variar entre tentativas do mesmo evento determinístico. Por isso nenhum dos
 * dois entra na identidade. Permanecem estáveis: id, nota, escopo, natureza,
 * origem, proveniência e `originalSectionId` — este último protege contra
 * colisão entre seções originais distintas.
 *
 * `decisionState`, `sectionLink`, `applicationState`, `updatedAt`,
 * `supersedesId`, snapshots e texto podem mudar por transições/histórico.
 */
private fun hasSameEventIdentity(a: DecisionRecord, b: DecisionRecord): Boolean =
    a.id == b.id &&
        a.noteId == b.noteId &&
        a.scope == b.scope &&
        a.kind == b.kind &&
        a.originalSectionId == b.originalSectionId &&
        a.source == b.source &&
        a.sourceMessageId == b.sourceMessageId &&
        a.sourceProposalId == b.sourceProposalId

private fun ineligibleReason(decision: DecisionRecord): IneligibleReason? {
    if (decision.decisionState != DecisionState.ACTIVE) {
        return IneligibleReason.NOT_ACTIVE
    }
    if (decision.kind != DecisionKind.CONFIRMED) {
        return IneligibleReason.NOT_CONFIRMED
    }
    val validLink = when (decision.scope) {
        DecisionScope.SECTION -> decision.sectionLink == SectionLink.VALID
        DecisionScope.NOTE -> decision.sectionLink == SectionLink.NOT_APPLICABLE
    }
    if (!validLink) {
        return IneligibleReason.SECTION_LINK_NOT_VALID
    }
    val subject = DecisionSubject.fromKey(decision.subjectKey)
    if (subject != null && subject.scope != decision.scope) {
        return IneligibleReason.SUBJECT_SCOPE_MISMATCH
    }
    return null
}

private fun sameDecisionLocation(
    incoming: DecisionRecord,
    candidate: DecisionRecord,
): Boolean {
    if (incoming.noteId != candidate.noteId) return false
    if (incoming.scope != candidate.scope) return false
    return when (incoming.scope) {
        DecisionScope.SECTION -> incoming.sectionId == candidate.sectionId
        DecisionScope.NOTE -> true
    }
}

private enum class SubjectMatch {
    NONE,
    KNOWN,
    LEGACY,
    UNKNOWN,
}

private fun subjectMatch(
    incoming: DecisionRecord,
    candidate: DecisionRecord,
): SubjectMatch {
    val incomingKey = incoming.subjectKey
    val candidateKey = candidate.subjectKey

    if (incomingKey == null || candidateKey == null) {
        // Regra 7: fallback legado só entre dois STRUCTURE_ACCEPT de SECTION,
        // da mesma nota/seção. A localização já foi validada pelo chamador.
        return if (
            incoming.scope == DecisionScope.SECTION &&
            candidate.scope == DecisionScope.SECTION &&
            incoming.source == DecisionSource.STRUCTURE_ACCEPT &&
            candidate.source == DecisionSource.STRUCTURE_ACCEPT
        ) {
            SubjectMatch.LEGACY
        } else {
            SubjectMatch.NONE
        }
    }

    val incomingSubject = DecisionSubject.fromKey(incomingKey)
    val candidateSubject = DecisionSubject.fromKey(candidateKey)
    if (incomingSubject != null && candidateSubject != null) {
        return if (incomingSubject == candidateSubject) {
            SubjectMatch.KNOWN
        } else {
            SubjectMatch.NONE
        }
    }

    // Nenhuma das chaves é conhecida. Só uma chave textual igual indica
    // possível mesmo assunto — e ainda assim o resultado será ambíguo.
    if (incomingSubject == null && candidateSubject == null) {
        return if (incomingKey == candidateKey) SubjectMatch.UNKNOWN else SubjectMatch.NONE
    }

    // Uma chave conhecida e outra desconhecida nunca são tratadas como o
    // mesmo assunto: desconhecido não é curinga.
    return SubjectMatch.NONE
}

/*
 * NOTAS PARA B2B/B2C (não implementadas nesta etapa)
 *
 * 1. Confirmação antes ou depois de alterar a estrutura:
 *    B2C deve perguntar DEPOIS do aceite B1 já transacional, para não
 *    bloquear a aplicação da estrutura. A pergunta é sobre memória, não
 *    sobre reescrever o texto.
 *
 * 2. Memória versus estrutura aplicada:
 *    applicationState/hasCurrentApplication continuam sendo a autoridade
 *    sobre texto aplicado. Supersessão altera somente o ledger; não reescreve
 *    a seção. A UI não deve dizer que o texto mudou.
 *
 * 3. Concorrência e idempotência na supersessão:
 *    B2B deve usar UPDATE condicional (... WHERE decisionState = 'ACTIVE'),
 *    conferir a contagem de linhas e tratar o mesmo par como idempotente.
 *    O upsertAll atual não impede duas substitutas concorrentes.
 *
 * 4. originalSectionId após remapeamento:
 *    validateSupersession exige originalSectionId igual. B2B precisa decidir
 *    se compara a seção atual (e preserva originais distintos) ou se exige
 *    remapeamento prévio. Não alterar essa regra sem teste A→B→substituição.
 *
 * 5. Evitar perguntas repetidas após "Manter as duas":
 *    B2D/B2E precisa de um acknowledgment persistente (preferência ou
 *    relação) ou de uma regra de exibição única por sessão. A ausência de
 *    supersessão já preserva as duas decisões; o problema é só repetição de
 *    pergunta.
 *
 * 6. Identidade sem sourceMessageId e sem sourceProposalId:
 *    decisões futuras que não tiverem NENHUMA dessas proveniências exigem uma
 *    política explícita de identidade antes de qualquer transição persistente
 *    (B2B). Não usar texto, createdAt, sectionId atual ou similaridade como
 *    substituto: o contrato de origem desta B2A não cobre esse caso e ele deve
 *    ser decidido com testes próprios.
 */
