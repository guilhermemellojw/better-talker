package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.ChatCodec
import com.bettertalker.app.data.util.RefDetector

/**
 * Teto do resumo textual de um card no histórico.
 *
 * O histórico aplica [MAX_HISTORY_CHARS] (500) por turno já rotulado. O card
 * precisa caber junto do rótulo de foco ("[Discurso inteiro] " etc.), por isso
 * o resumo é construído com folga — sem aumentar os limites globais.
 */
internal const val MAX_CARD_HISTORY_CHARS = 460

/** Cabeçalho curto do card; preserva ponto/título antes do corpo. */
private const val MAX_CARD_HEADER_CHARS = 110

/**
 * Monta o cabeçalho preservando o aviso de proveniência (avaliação, não
 * fonte factual). Se o título for longo, ele é truncado — nunca o aviso.
 */
private fun cardHeader(prefix: String, title: String, suffix: String): String {
    val budget = (MAX_CARD_HEADER_CHARS - prefix.length - suffix.length)
        .coerceAtLeast(8)
    return prefix + compactHistoryText(title, budget) + suffix
}

/** Compacta texto livre em uma linha, sem inventar conteúdo. */
private fun compactHistoryText(raw: String, maxChars: Int): String {
    val clean = raw.replace(Regex("\\s+"), " ").trim()
    if (maxChars <= 0) return ""
    if (clean.length <= maxChars) return clean
    if (maxChars == 1) return "…"
    return clean.take(maxChars - 1).trimEnd() + "…"
}

/** Itens numerados na ordem original; itens vazios não viram número solto. */
private fun numberedHistoryItems(
    items: List<String>,
    limit: Int,
    perItem: Int,
): String = items
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .take(limit)
    .mapIndexed { index, text ->
        "${index + 1}) ${compactHistoryText(text, perItem)}"
    }
    .joinToString("; ")

/**
 * Resumo compacto e determinístico de um card.
 *
 * Prioriza o identificador do card e a lista principal (melhorias/passos/
 * prioridades), reservando espaço para a lista secundária (fortes/achados/
 * pontos) quando ela existe. O builder nunca entrega um item numerado sem
 * conteúdo e não depende do truncamento cego de [truncateHistoryText].
 */
private fun cardHistorySummary(
    header: String,
    primaryLabel: String,
    primary: List<String>,
    secondaryLabel: String? = null,
    secondary: List<String> = emptyList(),
): String {
    val head = compactHistoryText(header, MAX_CARD_HEADER_CHARS)
    val out = StringBuilder(head)
    val primaryClean = primary.map { it.trim() }.filter { it.isNotEmpty() }
    val secondaryClean = secondary.map { it.trim() }.filter { it.isNotEmpty() }
    val secondaryReserve =
        if (secondaryLabel != null && secondaryClean.isNotEmpty()) 88 else 0
    val primaryPrefix = " $primaryLabel: "
    val primaryAvailable =
        MAX_CARD_HISTORY_CHARS - out.length - primaryPrefix.length - secondaryReserve
    if (primaryClean.isEmpty()) {
        out.append(primaryPrefix).append("nenhum item registrado.")
    } else {
        var perItem = (primaryAvailable / primaryClean.size).coerceAtLeast(28)
        var text = numberedHistoryItems(primaryClean, 3, perItem)
        while (text.length > primaryAvailable && perItem > 20) {
            perItem -= 4
            text = numberedHistoryItems(primaryClean, 3, perItem)
        }
        out.append(primaryPrefix).append(text)
    }
    if (secondaryLabel != null) {
        if (secondaryClean.isNotEmpty()) {
            val secondaryPrefix = " $secondaryLabel: "
            val secondaryAvailable = MAX_CARD_HISTORY_CHARS - out.length - secondaryPrefix.length
            if (secondaryAvailable >= 24) {
                var perItem = (secondaryAvailable / secondaryClean.size).coerceAtLeast(20)
                var text = numberedHistoryItems(secondaryClean, 2, perItem)
                while (text.length > secondaryAvailable && perItem > 16) {
                    perItem -= 4
                    text = numberedHistoryItems(secondaryClean, 2, perItem)
                }
                if (text.isNotBlank()) out.append(secondaryPrefix).append(text)
            }
        }
    }
    return out.toString().trim()
}

private fun reviewHistorySummary(map: Map<String, String>): String? {
    val report = PointReviewCodec.decode(map["report"].orEmpty()) ?: return null
    val quality = report.quality
        ?.takeIf { report.qualityStatus == PointQualityStatus.COMPLETED }
    val improvements = if (quality != null) {
        quality.improvements.mapNotNull { improvement ->
            val issue = improvement.issue.trim()
            if (issue.isBlank()) return@mapNotNull null
            val why = improvement.why.trim()
            val label = pointQualityCategoryLabel(improvement.category)
            if (why.isBlank()) "$label: $issue" else "$label: $issue — $why"
        }
    } else {
        report.improvements
    }
    val strengths = if (quality != null) {
        quality.positives.map { it.observation }
    } else {
        report.strengths
    }
    val header = cardHeader(
        prefix = "Card do Mentor — revisão do Ponto ${report.pointNumber} “",
        title = report.title,
        suffix = "”; avaliação do texto, não fonte factual.",
    )
    return cardHistorySummary(
        header = header,
        primaryLabel = "Melhorias",
        primary = improvements,
        secondaryLabel = "Fortes",
        secondary = strengths,
    )
}

private fun coachingHistorySummary(map: Map<String, String>): String? {
    val report = PointCoachingCodec.decode(map["report"].orEmpty()) ?: return null
    val header = cardHeader(
        prefix = "Card do Mentor — coaching do Ponto ${report.pointNumber} “",
        title = report.title,
        suffix = "”; avaliação do texto, não fonte factual.",
    )
    val steps = report.steps
    val findings = report.findings.map { finding ->
        "${finding.label} (${finding.level.name}): ${finding.detail}"
    }
    return cardHistorySummary(
        header = header,
        primaryLabel = "Passos",
        primary = steps,
        secondaryLabel = "Achados",
        secondary = findings,
    )
}

private fun diagnosisHistorySummary(map: Map<String, String>): String? {
    val report = OutlineDiagnosisCodec.decode(map["report"].orEmpty()) ?: return null
    val headline = report.headline.trim().ifBlank { report.summary.trim() }
    val header = if (headline.isBlank()) {
        "Card do Mentor — diagnóstico do esboço; avaliação, não fonte factual."
    } else {
        cardHeader(
            prefix = "Card do Mentor — diagnóstico do esboço: “",
            title = headline,
            suffix = "”; avaliação, não fonte factual.",
        )
    }
    val primary = report.priorities.ifEmpty {
        listOf(report.summary).filter { it.isNotBlank() }
    }
    val primaryLabel = if (report.priorities.isNotEmpty()) "Prioridades" else "Resultado"
    val points = report.points.map { point ->
        "Ponto ${point.number}: ${point.title} (${point.level.name})"
    }
    return cardHistorySummary(
        header = header,
        primaryLabel = primaryLabel,
        primary = primary,
        secondaryLabel = "Pontos",
        secondary = points,
    )
}

private fun ideasHistorySummary(map: Map<String, String>): String {
    val cards = ChatCodec.cardsFromJson(map["cards"].orEmpty())
    val section = map["section"].orEmpty().trim()
    val sectionLabel = if (section.isNotBlank()) " para “$section”" else ""
    if (cards.isEmpty()) {
        val prefix = if (section.isNotBlank()) "Ideias para “$section”" else "Ideias"
        return "$prefix (ideias)"
    }
    val items = cards.mapNotNull { card ->
        val title = card.title.trim()
        val body = compactHistoryText(card.body, 80)
        when {
            title.isNotBlank() && body.isNotBlank() -> "$title — $body"
            title.isNotBlank() -> title
            body.isNotBlank() -> body
            else -> null
        }
    }
    val header = "Card do Mentor — ideias$sectionLabel (ideias); sugestões, não fonte factual."
    return cardHistorySummary(
        header = header,
        primaryLabel = "Ideias",
        primary = items,
    )
}

private val NUMBERED_LINE = Regex(
    """^\s*(?:[-*•]\s*)?(?:\*\*|__)?\s*(\d{1,2})[.)]\s*(.*)$"""
)

private val SUGGESTION_TAG = Regex(
    """[〈<]/?\s*sugest[aã]o\s*[〉>]""",
    RegexOption.IGNORE_CASE,
)

/** Remove marcação de ênfase/tags do markdown usado nas listas do Copilot. */
private fun cleanListItem(raw: String): String =
    raw.replace(Regex("""\*\*|__"""), "")
        .replace(SUGGESTION_TAG, "")
        .trim()

/**
 * Resumo de uma LISTA numerada longa do próprio Copilot (quando o caminho
 * remoto devolve ideias como texto, não como card `ideas`).
 *
 * O truncamento genérico de 500 chars preserva início+fim e pode perder o
 * item 2 no meio. Aqui a ordem e os itens numerados são preservados, com
 * orçamento por item. Sem lista (≥2 itens) devolve o texto original.
 */
internal fun compactAssistantListForHistory(
    text: String,
    maxChars: Int = MAX_CARD_HISTORY_CHARS,
): String {
    val clean = text.replace("\r\n", "\n").trim()
    if (clean.length <= maxChars) return clean
    val lines = clean.split('\n')
    val firstNumber = lines.indexOfFirst { NUMBERED_LINE.matches(it) }
    if (firstNumber < 0) return clean
    val intro = lines.take(firstNumber)
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
    val items = mutableListOf<String>()
    var current: StringBuilder? = null
    fun flush() {
        current?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { items += it }
        current = null
    }
    for (line in lines.drop(firstNumber)) {
        val match = NUMBERED_LINE.find(line)
        if (match != null) {
            flush()
            current = StringBuilder(cleanListItem(match.groupValues[2]))
        } else if (current != null && line.trim().isNotBlank()) {
            current?.append(' ')?.append(cleanListItem(line))
        }
    }
    flush()
    if (items.size < 2) return clean
    val header = buildString {
        append("Lista anterior do Copilot")
        intro?.let { append(": ").append(compactHistoryText(it, 80)) }
        append(" — ")
    }
    val selected = items.take(4)
    val available = maxChars - header.length
    var perItem = (available / selected.size).coerceAtLeast(28)
    var body = numberedHistoryItems(selected, 4, perItem)
    while (body.length > available && perItem > 20) {
        perItem -= 4
        body = numberedHistoryItems(selected, 4, perItem)
    }
    return (header + body).take(maxChars).trim()
}

/**
 * Converte uma mensagem persistida do chat em texto legível para o histórico
 * do prompt.
 *
 * Antes, `conversationTurnsScoped` usava `e.kind` como texto: o modelo recebia
 * "ideas", "refs", "sections" ou "draft" — sem saber o que havia ali. Agora
 * cada payload vira um resumo curto e fiel:
 * - ideas: títulos + trecho do conteúdo;
 * - review/coaching/diagnosis: ponto, listas numeradas e aviso de que é
 *   avaliação do Mentor, não fonte factual;
 * - refs: rótulos das referências;
 * - sections: títulos;
 * - draft: título + primeiras linhas;
 * - proposal: explicação/pergunta persistida;
 * - text/bases: texto.
 *
 * Nunca devolve o nome do `kind`. Puro/testável.
 */
fun readableChatTurn(kind: String, payload: String): String {
    val map = ChatCodec.unescMap(payload)
    return when (kind) {
        "text" -> {
            val text = map["text"].orEmpty()
            if (map["cancelled"] == "1") {
                if (text.isBlank()) {
                    "Copilot (interrompido antes de gerar texto)"
                } else {
                    "Copilot (interrompido): ${text.take(600).trim()}"
                }
            } else {
                text
            }
        }

        "ideas" -> ideasHistorySummary(map)

        "review" -> reviewHistorySummary(map) ?: map["text"].orEmpty()

        "coaching" -> coachingHistorySummary(map) ?: map["text"].orEmpty()

        "diagnosis" -> diagnosisHistorySummary(map) ?: map["text"].orEmpty()

        "refs", "outline_refs" -> {
            val detected = RefDetector.detectedFromJson(map["detected"].orEmpty())
            val labels = detected.map { it.raw }.filter { it.isNotBlank() }.distinct()
            val title = map["title"].orEmpty().trim().ifBlank { "Referências" }
            if (labels.isEmpty()) title else "$title: ${labels.joinToString("; ")}"
        }

        "sections" -> {
            val items = ChatCodec.sectionsFromJson(map["items"].orEmpty())
            val titles = items.map { it.title }.filter { it.isNotBlank() }
            val title = map["title"].orEmpty().trim().ifBlank { "Seções" }
            if (titles.isEmpty()) title else "$title: ${titles.joinToString("; ")} (seções)"
        }

        "draft" -> {
            val (title, items) = ChatCodec.draftFromJson(payload)
            val included = items.filter { it.included && it.title.isNotBlank() }
            val firstLines = items
                .mapNotNull { item ->
                    item.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                }
                .filter { it.isNotBlank() }
                .take(2)
            buildString {
                append(title.ifBlank { "Rascunho" })
                if (included.isNotEmpty()) {
                    append(": ").append(included.joinToString("; ") { it.title })
                }
                if (firstLines.isNotEmpty()) {
                    append(" — ").append(firstLines.joinToString(" "))
                }
            }
        }

        "proposal" -> map["text"].orEmpty().trim()
            .ifBlank { map["summary"].orEmpty().trim() }
            .ifBlank { map["question"].orEmpty().trim() }
            .ifBlank { "Proposta de edição" }

        "bases" -> map["text"].orEmpty().ifBlank { "Bases" }

        // Desconhecido: usa o texto do payload, nunca o nome do kind.
        else -> map["text"].orEmpty()
    }.trim()
}

/** Versão curta para memória RAG (1 linha). */
fun readableChatTurnSummary(kind: String, payload: String, maxChars: Int = 120): String =
    readableChatTurn(kind, payload)
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxChars)

/**
 * Rótulo de foco de um turno. O histórico é da NOTA (não filtrado por seção);
 * o rótulo lembra ao modelo de onde veio cada mensagem.
 */
fun chatFocusLabel(sectionId: String?, sectionTitle: String?): String =
    if (sectionId.isNullOrBlank()) {
        "Discurso inteiro"
    } else {
        val title = sectionTitle?.trim().orEmpty()
        "Tópico: ${title.ifBlank { "seção" }}"
    }

/** Prefixa o turno com o foco em que ele foi produzido. */
fun labelChatTurn(text: String, sectionId: String?, sectionTitle: String?): String =
    "[${chatFocusLabel(sectionId, sectionTitle)}] $text"
