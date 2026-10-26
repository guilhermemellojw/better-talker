package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.util.normalizeText

/**
 * Fase 16 — paridade do chat nativo com a F15 (web).
 *
 * Contratos do `chatEngine` web reproduzidos como funções puras: intenção
 * interna, janela de histórico, continuidade, atalhos, rótulo de contexto,
 * aviso offline e erro amigável. Nenhuma regra de fidelidade é inventada aqui —
 * o texto espelha o web para que as duas plataformas decidam igual.
 *
 * Puro/testável: sem IO, sem Android, sem estado.
 */

/**
 * Mensagens de contexto enviadas ao prompt.
 *
 * Fase 1 (contexto fiel): 12 mensagens (antes 6). A janela curta perdia a
 * pergunta imediatamente anterior em conversas longas e fazia o modelo
 * responder "sim o quê?".
 */
const val MAX_HISTORY_MESSAGES = 12

/** Caracteres máximos por mensagem ao serializar o histórico. */
const val MAX_HISTORY_CHARS = 500

/** Início preservado de uma mensagem longa (Fase 1). */
const val HISTORY_HEAD_CHARS = 200

/** Fim preservado de uma mensagem longa — onde normalmente está a pergunta. */
const val HISTORY_TAIL_CHARS = 300

/**
 * Teto total do histórico em caracteres (~6.000 tokens a 4 chars/token).
 * O que vier primeiro entre [MAX_HISTORY_MESSAGES] e este teto vence.
 */
const val MAX_HISTORY_TOTAL_CHARS = 24_000

/** Seleção máxima levada ao prompt (§17 F18): trecho, não o discurso. */
const val MAX_SELECTION_CHARS = 2000

/** Ações do copilot. Não confundir com [com.bettertalker.app.data.util.ChatIntent.Intent],
 * que é o roteador de ações locais do app (ex.: "resumir", "sync"). */
enum class ChatAction { CHAT, CRITIQUE, HOOK, CUES, REWRITE, SHORTEN }

/**
 * Intenção inferida da mensagem. [trainingCategory] null = trilha CONTENT
 * (só conteúdo factual); não nulo = trilha TRAINING para COMPLEMENTAR, nunca
 * como fonte de fato.
 */
data class InferredIntent(
    val action: ChatAction,
    val trainingCategory: TrainingCategory?,
    val verification: Boolean
)

/** Uma mensagem da conversa, para serialização no prompt. */
data class ChatTurn(val fromMe: Boolean, val text: String)

/**
 * Mensagem válida para envio. Vazio/branco é bloqueado — o composer nunca
 * envia nada. Puro/testável.
 */
fun validateOutgoingMessage(raw: String): String? {
    val t = raw.trim()
    return if (t.isEmpty()) null else t
}

/**
 * #26-fix-b: o texto enviado veio MESMO do clipboard? Comparação exata
 * (trim) — "material colado" deixa de ser só tamanho. Puro/testável.
 */
fun clipboardMatches(text: String, clipText: String?): Boolean {
    val t = text.trim()
    val c = clipText?.trim().orEmpty()
    return t.isNotEmpty() && c == t
}

/**
 * Intenção interna a partir da linguagem natural. NUNCA exibida ao usuário
 * (§12 F15): serve para escolher a categoria BE/TH a recuperar e o foco do
 * prompt. A ordem importa — a primeira regra que casa vence, então
 * "melhorar essa introdução" é hook, não rewrite.
 *
 * Puro/testável.
 */
fun inferIntent(message: String, isFirstMessage: Boolean): InferredIntent {
    // Espelhando o web: normaliza sem acentos e envolve em espaços para
    // permitir âncoras de limite de palavra.
    val n = " ${normalizeText(message)} "
    // Mesma semântica do web: alternância simples sobre o texto normalizado.
    // Sem limite de palavra de propósito — "melhorar" precisa casar em
    // `/(melhor|melhore|...)/`, e o web conta com esse prefixo.
    fun has(vararg pats: String) =
        Regex("(?:" + pats.joinToString("|") + ")").containsMatchIn(n)

    // Verificação factual pede trilha CONTENT: treinamento nunca é prova.
    if (has("esta correta", "esta certo", "esta mesmo", "realmente esta",
            "realmente esta na", "conferir", "confira", "verificar", "verifique",
            "tem apoio", "suporte")) {
        return InferredIntent(ChatAction.CRITIQUE, null, verification = true)
    }
    if (has("introducao", "abertura", "comeco", "inicio", "gancho", "ganchos", "hook")) {
        return InferredIntent(ChatAction.HOOK, TrainingCategory.INTRODUCTION, false)
    }
    if (has("ilustracao", "ilustra", "analogia", "exemplo", "metafora", "historia")) {
        return InferredIntent(ChatAction.CUES, TrainingCategory.ILLUSTRATION, false)
    }
    if (has("aplicacao", "aplicar", "pratica", "como aplico")) {
        return InferredIntent(ChatAction.REWRITE, TrainingCategory.APPLICATION, false)
    }
    if (has("transicao", "ligacao", "ponte")) {
        return InferredIntent(ChatAction.REWRITE, TrainingCategory.TRANSITION, false)
    }
    if (has("natural", "naturalidade", "seca", "artificial", "espontane", "solto", "roboti")) {
        return InferredIntent(ChatAction.REWRITE, TrainingCategory.NATURALNESS, false)
    }
    if (has("mais curta", "curta", "resumir", "resumo", "encurtar", "enxugar", "cortar",
            "conciso", "direto ao ponto", "mais simples", "simplificar", "simplific")) {
        return InferredIntent(ChatAction.SHORTEN, TrainingCategory.CLARITY, false)
    }
    if (has("melhor", "melhore", "refinar", "aprimorar", "polir")) {
        val cat = if (isFirstMessage) TrainingCategory.CLARITY else TrainingCategory.NATURALNESS
        return InferredIntent(ChatAction.REWRITE, cat, false)
    }
    if (has("apresentar", "falar", "dizer", "entrega", "palco", "oral")) {
        return InferredIntent(ChatAction.CRITIQUE, TrainingCategory.DELIVERY, false)
    }
    // Pergunta factual/genérica: só conteúdo (§15 F15).
    return InferredIntent(ChatAction.CRITIQUE, null, false)
}

/**
 * Corta uma mensagem longa preservando início E fim. O fim é onde costuma
 * estar a pergunta que o usuário vai responder no próximo turno.
 * Puro/testável.
 */
fun truncateHistoryText(
    text: String,
    maxChars: Int = MAX_HISTORY_CHARS,
): String {
    val clean = text.trim()
    if (clean.length <= maxChars) return clean
    return clean.take(HISTORY_HEAD_CHARS) + "…" + clean.takeLast(HISTORY_TAIL_CHARS)
}

/**
 * Última pergunta feita pelo Copilot na conversa (não a do usuário).
 * Usada para preservar a continuidade mesmo quando a mensagem foi cortada
 * ou caiu fora da janela serializada. Puro/testável.
 */
fun lastCopilotQuestion(turns: List<ChatTurn>): String? {
    val text = turns.lastOrNull { !it.fromMe && it.text.isNotBlank() }?.text
        ?: return null
    val questions = Regex("""[^?]{1,300}\?""").findAll(text).toList()
    return questions.lastOrNull()?.value?.trim()?.takeIf { it.length >= 3 }
}

/**
 * Turns que REALMENTE entram no prompt: janela de [limit] + teto total.
 * Usado pelo `recentHistory` e pela telemetria — a métrica precisa medir o
 * que foi enviado, não o histórico bruto do banco.
 *
 * Puro/testável.
 */
fun effectiveHistoryTurns(
    turns: List<ChatTurn>,
    limit: Int = MAX_HISTORY_MESSAGES,
    maxTotalChars: Int = MAX_HISTORY_TOTAL_CHARS,
): List<ChatTurn> {
    val all = turns.filter { it.text.isNotBlank() }
    if (all.isEmpty()) return emptyList()
    val recent = all.takeLast(limit.coerceAtLeast(1))
    // Orçamento total: entra do mais novo para o mais antigo; a mensagem
    // mais recente é sempre mantida.
    val selected = ArrayDeque<ChatTurn>()
    var used = 0
    for (turn in recent.asReversed()) {
        val cost = minOf(turn.text.length, MAX_HISTORY_CHARS) + 12
        if (selected.isNotEmpty() && used + cost > maxTotalChars) break
        selected.addFirst(turn)
        used += cost
    }
    return selected.toList()
}

/**
 * Conversa anterior serializada para o prompt.
 *
 * Fase 1 (contexto fiel):
 * - janela de [MAX_HISTORY_MESSAGES] mensagens OU [MAX_HISTORY_TOTAL_CHARS],
 *   o que vier primeiro;
 * - mensagem longa preserva início + fim ([truncateHistoryText]);
 * - a última pergunta do Copilot é sempre preservada, mesmo que a mensagem
 *   tenha sido cortada ou esteja fora da janela;
 * - a mensagem mais recente (ex.: "Sim") nunca é descartada.
 *
 * Puro/testável.
 */
fun recentHistory(turns: List<ChatTurn>, limit: Int = MAX_HISTORY_MESSAGES): String {
    val selected = effectiveHistoryTurns(turns, limit)
    if (selected.isEmpty()) return ""

    val lines = selected.map { t ->
        val who = if (t.fromMe) "Usuário" else "Copilot"
        "$who: ${truncateHistoryText(t.text)}"
    }.toMutableList()

    // A pergunta pode ter sido cortada (meio da mensagem) ou estar fora da
    // janela. Nesse caso, entra como linha explícita no fim do histórico.
    val question = lastCopilotQuestion(turns)
    if (question != null && lines.none { it.contains(question) }) {
        lines += "Pergunta anterior do Copilot: $question"
    }

    return "--- CONVERSA ANTERIOR (contexto; continue a partir dela) ---\n" +
        lines.joinToString("\n") + "\n--- FIM DA CONVERSA ANTERIOR ---\n"
}

/**
 * Texto do "brief" da conversa: histórico + continuidade + a mensagem atual.
 * Sem histórico vira só a mensagem, então a primeira pergunta não carrega
 * seções vazias no prompt. Puro/testável.
 */
fun chatBriefToText(message: String, history: List<ChatTurn>, isFirstMessage: Boolean): String {
    val parts = mutableListOf<String>()
    val historySection = recentHistory(history)
    if (historySection.isNotEmpty()) parts += historySection
    if (!isFirstMessage) {
        parts += "Continuidade: esta mensagem continua a conversa anterior — " +
            "\"isso/essa parte\" referem-se ao que já foi discutido. " +
            "Não peça informações que já foram dadas."
        parts += "Os turnos anteriores podem ter sido feitos em outro foco " +
            "(tópico ou discurso inteiro); use-os como contexto. " +
            "O foco atual é o indicado abaixo."
        lastCopilotQuestion(history)?.let { question ->
            parts += "Última pergunta do Copilot: \"$question\""
        }
        parts += "Se a mensagem atual for uma resposta curta (\"sim\", \"não\", " +
            "\"pode\", \"manda\"), ela responde à última pergunta do Copilot. " +
            "Continue a partir dela; não peça para o usuário repetir."
    }
    parts += "Mensagem do usuário: \"$message\""
    return parts.joinToString("\n\n")
}

/** Marcador de consenso emitido pelo modelo (instruído na cauda do chat). */
const val DECISION_OPEN = "〈decisão〉"
const val DECISION_CLOSE = "〈/decisão〉"

/**
 * A partir de N turnos do usuário, a memória da construção é (re)gerada —
 * in-place, sem acumular. Mantém o system estável entre turnos (cache).
 */
const val SUMMARY_EVERY_TURNS = 8

/** Teto de decisões carregadas no bloco (fio recente, sem poluir). */
const val SUMMARY_MAX_DECISIONS = 5

/** Pistas de decisão do usuário (texto normalizado, sem acento). */
private val DECISION_HINTS = Regex(
    "\\b(decidido|decidimos|combinado|fechado|vamos fazer assim|pode ser assim|" +
        "fica assim|anota|anote|registra isso)\\b"
)

/**
 * Memória da construção em conjunto: o que foi decidido + próximo passo.
 * Por conversa (o histórico já é escopado); sem persistência própria.
 */
data class RollingSummary(
    val sectionTitle: String = "",
    val decisions: List<String> = emptyList(),
    val nextStep: String = "",
    val userTurns: Int = 0,
)

/**
 * Extrai decisões de um texto: tags 〈decisão〉 do modelo primeiro;
 * sem tags, frases do usuário com pista explícita ("decidido", ...).
 * Puro/testável.
 */
fun extractDecisions(text: String): List<String> {
    val tagged = Regex(
        Regex.escape(DECISION_OPEN) + "(.*?)" + Regex.escape(DECISION_CLOSE),
        RegexOption.DOT_MATCHES_ALL,
    ).findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (tagged.isNotEmpty()) return tagged
    if (!DECISION_HINTS.containsMatchIn(" ${normalizeText(text)} ")) return emptyList()
    return text.split(Regex("[.!?\\n]+"))
        .map { it.trim() }
        .filter { it.length > 12 }
        .take(2)
}

/**
 * (Re)gera o resumo a partir do histórico escopado: decisões das respostas
 * + decisões do usuário, fio recente primeiro, teto aplicado. Puro/testável.
 */
fun buildRollingSummary(turns: List<ChatTurn>, sectionTitle: String): RollingSummary {
    val userTurns = turns.count { it.fromMe && it.text.isNotBlank() }
    val decisions = turns
        .flatMap { extractDecisions(it.text) }
        .takeLast(SUMMARY_MAX_DECISIONS)
    val nextStep = turns.lastOrNull { it.fromMe }?.text?.trim()?.take(140).orEmpty()
    return RollingSummary(sectionTitle.trim(), decisions, nextStep, userTurns)
}

/**
 * Bloco ## MEMÓRIA DA CONSTRUÇÃO para o system (antes das 6 mensagens).
 * Null quando não há o que lembrar (conversa curta sem decisões) — nesse
 * caso o prompt segue idêntico ao anterior. Puro/testável.
 */
fun renderMemoryBlock(summary: RollingSummary): String? {
    if (summary.decisions.isEmpty() && summary.userTurns < SUMMARY_EVERY_TURNS) return null
    return buildString {
        appendLine("## MEMÓRIA DA CONSTRUÇÃO (o que já foi decidido — continue daqui)")
        if (summary.sectionTitle.isNotBlank()) appendLine("Seção atual: ${summary.sectionTitle}")
        summary.decisions.forEach { appendLine("- Decisão: $it") }
        if (summary.nextStep.isNotBlank()) appendLine("Próximo passo: ${summary.nextStep}")
    }.trim()
}

/**
 * Mescla decisões novas no agreedApproach da seção (sem duplicar,
 * case-insensitive; teto de 500 chars). Null quando nada muda.
 * Puro/testável.
 */
fun mergeApproach(current: String?, decisions: List<String>): String? {
    val fresh = decisions.map { it.trim() }.filter { it.isNotEmpty() }
    if (fresh.isEmpty()) return null
    return mergeApproachText(current, fresh.joinToString("\n") { "- $it" })
}

/**
 * Mescla TEXTO novo no agreedApproach (uma ou várias linhas), com dedupe
 * por trecho (case-insensitive) e teto de [MAX_APPROACH_CHARS]. Aceita
 * tanto linhas "- item" quanto prosa livre. Null quando nada muda.
 * Puro/testável — usado pelo consenso do chat e pela proposta de
 * estrutura (#3c), para o campo não crescer sem limite.
 */
fun mergeApproachText(existing: String?, addition: String?): String? {
    val add = addition?.trim().orEmpty()
    if (add.isEmpty()) return null
    val base = existing?.trim().orEmpty()
    val sb = StringBuilder(base)
    for (line in add.split("\n")) {
        val d = line.trim().removePrefix("- ").trim()
        if (d.isEmpty()) continue
        if (sb.toString().lowercase().contains(d.lowercase())) continue
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append("- ").append(d)
    }
    val merged = sb.toString().trim().take(MAX_APPROACH_CHARS)
    if (merged.isEmpty() || merged == base) return null
    return merged
}

/** Teto do campo agreedApproach (compartilhado com o prompt do dossiê). */
const val MAX_APPROACH_CHARS = 500

/** Atalho que vira mensagem de usuário e entra no MESMO pipeline do chat livre. */
data class QuickAction(val id: String, val label: String, val message: String)

/** Atalhos da F15. Nenhum tem implementação própria: todos enviam [message]. */
val QUICK_ACTIONS: List<QuickAction> = listOf(
    QuickAction("improve-point", "Melhorar este ponto", "Melhore este ponto."),
    QuickAction("natural", "Deixar mais natural", "Deixe mais natural."),
    QuickAction("illustration", "Criar ilustração", "Crie uma ilustração para esse ponto."),
    QuickAction("verify", "Verificar", "Confira se isso tem apoio nas fontes.")
)

/** Sugestões de continuação mostradas após uma resposta. Também mensagens. */
val FOLLOW_UP_SUGGESTIONS: List<String> = listOf("Mais natural", "Mais curta", "Outra versão")

/**
 * Rótulo simples de contexto (§37 F15): bloco em foco, senão o discurso.
 * O usuário nunca digita id de bloco. Puro/testável.
 *
 * Fase 18 §16: seleção explícita tem prioridade sobre bloco e discurso.
 */
fun contextLabel(blockTitle: String?, speechTitle: String?, selectedText: String? = null): String {
    val sel = selectedText?.trim().orEmpty()
    if (sel.isNotEmpty()) return "trecho selecionado"
    val b = blockTitle?.trim().orEmpty()
    if (b.isNotEmpty()) return b
    val s = speechTitle?.trim().orEmpty()
    if (s.isNotEmpty()) return s
    return "sem contexto"
}

/** Códigos de erro do provider — espelha `ProviderErrorCode` do web. */
enum class ProviderErrorCode {
    TIMEOUT, NETWORK, AUTHENTICATION, RATE_LIMIT, INVALID_REQUEST,
    INVALID_RESPONSE, UNAVAILABLE, CANCELLED
}

/**
 * Texto humano para o usuário. Nunca devolve HTTP, stack trace, nome de
 * exceção ou o código técnico (§23 F15). Puro/testável.
 */
fun friendlyChatError(code: ProviderErrorCode): String = when (code) {
    ProviderErrorCode.CANCELLED ->
        "Geração cancelada. Nada foi alterado no discurso."
    ProviderErrorCode.TIMEOUT ->
        "Não consegui gerar a resposta a tempo. Verifique a conexão e tente novamente."
    ProviderErrorCode.AUTHENTICATION ->
        "A chave de IA não está válida. Confira nas Configurações para usar o Copilot remoto."
    ProviderErrorCode.RATE_LIMIT ->
        "Muitas perguntas seguidas. Espere um momento e tente de novo."
    ProviderErrorCode.NETWORK ->
        "Não consegui gerar a resposta agora.\nVerifique a conexão ou tente novamente."
    ProviderErrorCode.UNAVAILABLE ->
        "O assistente remoto está indisponível agora. Tente novamente em instantes."
    ProviderErrorCode.INVALID_REQUEST, ProviderErrorCode.INVALID_RESPONSE ->
        "Não consegui gerar a resposta agora. Tente novamente."
}

/** Mapeia Throwable para o código, sem vazar detalhe técnico. */
fun friendlyChatError(t: Throwable): String = when {
    t is java.util.concurrent.CancellationException -> friendlyChatError(ProviderErrorCode.CANCELLED)
    t is java.net.SocketTimeoutException -> friendlyChatError(ProviderErrorCode.TIMEOUT)
    t is java.net.UnknownHostException -> friendlyChatError(ProviderErrorCode.NETWORK)
    t is java.io.IOException -> friendlyChatError(ProviderErrorCode.NETWORK)
    else -> friendlyChatError(ProviderErrorCode.INVALID_RESPONSE)
}

/**
 * Estados de geração do turno (§24 F15). O composer só trava em
 * [SENDING]/[GENERATING]; [ERROR] e [CANCELLED] liberam o envio — é o bug
 * que a F15 encontrou e corrigiu. Puro/testável.
 */
sealed interface ChatRunState {
    data object Idle : ChatRunState
    data object Sending : ChatRunState
    data object Generating : ChatRunState
    data object Success : ChatRunState
    /** [message] já é texto humano; [code] permite "Tentar novamente". */
    data class Error(val message: String, val code: ProviderErrorCode? = null) : ChatRunState
    data class Cancelled(val message: String = friendlyChatError(ProviderErrorCode.CANCELLED)) : ChatRunState

    /** Só impede novo envio durante o turno em andamento. */
    val blocksComposer: Boolean get() = this is Sending || this is Generating

    val isTerminal: Boolean get() = this is Success || this is Error || this is Cancelled
}
