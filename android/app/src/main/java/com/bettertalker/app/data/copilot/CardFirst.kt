package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.edit.hashText

/**
 * Card-first, fase 1 — contrato puro do card de texto.
 *
 * O alvo é capturado na GERAÇÃO, nunca no clique. Subponto não é alvo de
 * inserção nesta fase: o card aponta para a seção e mostra o foco
 * ("Para: Ponto 2"). CANCELLED/ERROR são TurnPhase do M1, não CardStatus.
 */

enum class CardTargetKind { SECTION, NOTE }

enum class CardCapturedFrom {
    PUSH,
    ACTIVE_BLOCK,
    SELECTION,
    WHOLE,
    TRANSFORM,
    /** Destino escolhido depois da geração (NO_TARGET). */
    USER_PICK,
}

enum class CardOrigin { CHAT, ORATORY, TRANSFORM, LEGACY }

enum class CardInsertMode { APPEND, REPLACE }

/** Ação sugerida pelo fluxo (reescrita → substituir; geração → append). */
enum class CardSuggestedAction { APPEND, REPLACE }

enum class CardStatus {
    AVAILABLE,
    APPLIED,
    STALE,
    NO_TARGET,
    TARGET_MISSING,
    /** Visual/gate: o card é antigo, mas insert/substituir seguem válidos. */
    SUPERSEDED,
    DISMISSED,
    /** Só falha de insert/undo; cancelamento/erro são TurnPhase. */
    FAILED,
}

data class CardTarget(
    val kind: CardTargetKind,
    val id: String,
    val label: String,
    val baseHash: String,
    val capturedFrom: CardCapturedFrom,
    val capturedAt: Long,
    /** Ex.: "Ponto 2" quando o foco era um subponto da seção. */
    val focusLabel: String? = null,
) {
    val displayLabel: String
        get() = if (focusLabel.isNullOrBlank()) label else "$label · Para: $focusLabel"
}

data class CardPayload(
    val schema: Int = 1,
    val targetKind: CardTargetKind? = null,
    val targetId: String? = null,
    val targetLabel: String? = null,
    val baseHash: String? = null,
    val capturedFrom: CardCapturedFrom? = null,
    val capturedAt: Long? = null,
    val turnId: String? = null,
    val origin: CardOrigin = CardOrigin.CHAT,
    val summary: String = "",
    val gateMessageId: String? = null,
    val status: CardStatus = CardStatus.AVAILABLE,
    val appliedAt: Long? = null,
    val suggestedAction: CardSuggestedAction = CardSuggestedAction.APPEND,
) {
    fun toMap(): Map<String, String> = buildMap {
        put("cardSchema", schema.toString())
        targetKind?.let { put("cardTargetKind", it.name) }
        targetId?.let { put("cardTargetId", it) }
        targetLabel?.let { put("cardTargetLabel", it) }
        baseHash?.let { put("cardBaseHash", it) }
        capturedFrom?.let { put("cardCapturedFrom", it.name) }
        capturedAt?.let { put("cardCapturedAt", it.toString()) }
        turnId?.let { put("cardTurnId", it) }
        put("cardOrigin", origin.name)
        put("cardSummary", capCardSummary(summary))
        gateMessageId?.let { put("cardGateMessageId", it) }
        put("cardStatus", status.name)
        put("cardSuggestedAction", suggestedAction.name)
        appliedAt?.let { put("cardAppliedAt", it.toString()) }
    }

    companion object {
        fun fromMap(map: Map<String, String>): CardPayload? {
            val hasAny = map.keys.any { it.startsWith("card") }
            if (!hasAny) return null
            fun enumOrNull(name: String?): CardTargetKind? =
                name?.let { runCatching { CardTargetKind.valueOf(it) }.getOrNull() }
            fun captured(name: String?): CardCapturedFrom? =
                name?.let { runCatching { CardCapturedFrom.valueOf(it) }.getOrNull() }
            fun status(name: String?): CardStatus =
                name?.let { runCatching { CardStatus.valueOf(it) }.getOrNull() }
                    ?: CardStatus.AVAILABLE
            fun origin(name: String?): CardOrigin =
                name?.let { runCatching { CardOrigin.valueOf(it) }.getOrNull() }
                    ?: CardOrigin.CHAT
            return CardPayload(
                schema = map["cardSchema"]?.toIntOrNull() ?: 1,
                targetKind = enumOrNull(map["cardTargetKind"]),
                targetId = map["cardTargetId"]?.takeIf { it.isNotBlank() },
                targetLabel = map["cardTargetLabel"]?.takeIf { it.isNotBlank() },
                baseHash = map["cardBaseHash"]?.takeIf { it.isNotBlank() },
                capturedFrom = captured(map["cardCapturedFrom"]),
                capturedAt = map["cardCapturedAt"]?.toLongOrNull(),
                turnId = map["cardTurnId"]?.takeIf { it.isNotBlank() },
                origin = origin(map["cardOrigin"]),
                summary = capCardSummary(map["cardSummary"].orEmpty()),
                gateMessageId = map["cardGateMessageId"]?.takeIf { it.isNotBlank() },
                status = status(map["cardStatus"]),
                appliedAt = map["cardAppliedAt"]?.toLongOrNull(),
                suggestedAction = map["cardSuggestedAction"]?.let {
                    runCatching { CardSuggestedAction.valueOf(it) }.getOrNull()
                } ?: CardSuggestedAction.APPEND,
            )
        }
    }
}

/** Teto do resumo do card (histórico/card, não o texto aplicável). */
fun capCardSummary(text: String, maxChars: Int = 160): String {
    val oneLine = text
        .replace(Regex("""<[^>]+>"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars - 1).trimEnd() + "…"
}

fun cardTargetForSection(
    sectionId: String,
    label: String,
    contentHtml: String,
    capturedFrom: CardCapturedFrom,
    capturedAt: Long,
    focusLabel: String? = null,
): CardTarget = CardTarget(
    kind = CardTargetKind.SECTION,
    id = sectionId,
    label = label,
    baseHash = hashText(contentHtml),
    capturedFrom = capturedFrom,
    capturedAt = capturedAt,
    focusLabel = focusLabel,
)

fun cardTargetForNote(
    noteId: String,
    label: String,
    richHtml: String,
    capturedFrom: CardCapturedFrom,
    capturedAt: Long,
): CardTarget = CardTarget(
    kind = CardTargetKind.NOTE,
    id = noteId,
    label = label,
    baseHash = hashText(richHtml),
    capturedFrom = capturedFrom,
    capturedAt = capturedAt,
)

/**
 * Status efetivo no carregamento. `SUPERSEDED` é visual/gate; não é
 * terminal para insert. CANCELLED/ERROR não viram CardStatus.
 */
fun resolveCardStatus(
    payload: CardPayload,
    targetExists: Boolean,
    currentTargetHash: String?,
    superseded: Boolean = false,
): CardStatus {
    if (payload.status == CardStatus.APPLIED ||
        payload.status == CardStatus.DISMISSED ||
        payload.status == CardStatus.FAILED
    ) {
        return payload.status
    }
    if (payload.targetId.isNullOrBlank() || payload.targetKind == null) {
        return CardStatus.NO_TARGET
    }
    if (!targetExists) return CardStatus.TARGET_MISSING
    if (currentTargetHash != null && payload.baseHash != null &&
        currentTargetHash != payload.baseHash
    ) {
        return CardStatus.STALE
    }
    if (superseded) return CardStatus.SUPERSEDED
    return CardStatus.AVAILABLE
}

/** Insert/append permitido em AVAILABLE, STALE e SUPERSEDED (hash válido). */
fun canInsertCard(status: CardStatus): Boolean = when (status) {
    CardStatus.AVAILABLE, CardStatus.STALE, CardStatus.SUPERSEDED -> true
    else -> false
}

/** Replace só com hash válido; stale/replace é bloqueado por padrão. */
fun canReplaceCard(status: CardStatus, hashMatches: Boolean): Boolean =
    hashMatches && (status == CardStatus.AVAILABLE || status == CardStatus.SUPERSEDED)

/** O gate do "Sim" só aceita o card mais recente que é a última fala. */
fun canGateCard(
    status: CardStatus,
    isLastCopilotMessage: Boolean,
    applied: Boolean,
): Boolean = isLastCopilotMessage && !applied &&
    (status == CardStatus.AVAILABLE || status == CardStatus.STALE)

/** CANCELLED/ERROR são do turno: card read-only, sem insert/replace/gate. */
fun cardReadOnly(turnPhase: TurnPhase?): Boolean =
    turnPhase == TurnPhase.CANCELLED || turnPhase == TurnPhase.ERROR

fun cardActionsEnabled(turnPhase: TurnPhase?, status: CardStatus): Boolean =
    !cardReadOnly(turnPhase) && canInsertCard(status)

/**
 * Extrai o primeiro bloco `<texto-do-card>` completo. Um card por resposta;
 * o restante do texto continua sendo conversa. Puro/testável.
 */
fun extractCardText(raw: String, maxChars: Int = 20_000): String? {
    val open = raw.indexOf("<texto-do-card>")
    if (open < 0) return null
    val start = open + "<texto-do-card>".length
    val close = raw.indexOf("</texto-do-card>", start)
    if (close < 0) return null
    return raw.substring(start, close).trim().take(maxChars).takeIf { it.isNotBlank() }
}

/**
 * `NO_TARGET` → destino escolhido pelo usuário. O hash é capturado AGORA
 * (não no clique de inserir) e a UI mostra o aviso de revisão.
 */
fun applyUserPick(
    card: CardPayload,
    sectionId: String,
    label: String,
    contentHtml: String,
    now: Long,
): CardPayload = card.copy(
    targetKind = CardTargetKind.SECTION,
    targetId = sectionId,
    targetLabel = label,
    baseHash = hashText(contentHtml),
    capturedFrom = CardCapturedFrom.USER_PICK,
    capturedAt = now,
    status = CardStatus.AVAILABLE,
)

/** Chave estável do alvo; null quando o card não tem alvo. */
fun cardTargetKey(card: CardPayload): String? {
    val kind = card.targetKind ?: return null
    val id = card.targetId?.takeIf { it.isNotBlank() } ?: return null
    return "${kind.name}:$id"
}

/**
 * Um card novo no MESMO alvo marca os anteriores como SUPERSEDED (visual).
 * Cards de alvos diferentes não se supersedem. Puro/testável.
 */
fun supersededCardMessageIds(
    cardsInOrder: List<Pair<String, CardPayload>>,
): Set<String> {
    val lastByTarget = mutableMapOf<String, String>()
    cardsInOrder.forEach { (messageId, card) ->
        val key = cardTargetKey(card) ?: return@forEach
        lastByTarget[key] = messageId
    }
    return cardsInOrder
        .filter { (messageId, card) ->
            val key = cardTargetKey(card) ?: return@filter false
            lastByTarget[key] != messageId
        }
        .map { it.first }
        .toSet()
}

/**
 * Revisão do card: troca o conteúdo do PRIMEIRO bloco `<texto-do-card>`,
 * preservando a conversa antes/depois. Retorna null quando não há bloco.
 */
fun replaceCardTextBlock(raw: String, newText: String, maxChars: Int = 20_000): String? {
    val open = raw.indexOf("<texto-do-card>")
    if (open < 0) return null
    val start = open + "<texto-do-card>".length
    val close = raw.indexOf("</texto-do-card>", start)
    if (close < 0) return null
    val clean = newText
        .replace("</texto-do-card>", "")
        .trim()
        .take(maxChars)
    return raw.substring(0, start) + clean + raw.substring(close)
}

fun cardStatusLabel(status: CardStatus): String = when (status) {
    CardStatus.AVAILABLE -> "Pronto para inserir"
    CardStatus.APPLIED -> "Inserido"
    CardStatus.STALE -> "O texto mudou"
    CardStatus.NO_TARGET -> "Escolha o destino"
    CardStatus.TARGET_MISSING -> "Destino não existe mais"
    CardStatus.SUPERSEDED -> "Sugestão anterior"
    CardStatus.DISMISSED -> "Sugestão dispensada"
    CardStatus.FAILED -> "Falha na ação"
}
