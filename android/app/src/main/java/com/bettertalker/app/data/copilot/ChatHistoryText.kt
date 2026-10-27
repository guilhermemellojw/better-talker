package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.ChatCodec
import com.bettertalker.app.data.util.RefDetector

/**
 * Converte uma mensagem persistida do chat em texto legível para o histórico
 * do prompt.
 *
 * Antes, `conversationTurnsScoped` usava `e.kind` como texto: o modelo recebia
 * "ideas", "refs", "sections" ou "draft" — sem saber o que havia ali. Agora
 * cada payload vira um resumo curto e fiel:
 * - ideas: títulos dos cartões + "(ideias)";
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

        "ideas" -> {
            val cards = ChatCodec.cardsFromJson(map["cards"].orEmpty())
            val titles = cards.map { it.title }.filter { it.isNotBlank() }
            val section = map["section"].orEmpty().trim()
            val prefix = if (section.isNotBlank()) "Ideias para “$section”" else "Ideias"
            if (titles.isEmpty()) prefix
            else "$prefix: ${titles.joinToString("; ")} (ideias)"
        }

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
