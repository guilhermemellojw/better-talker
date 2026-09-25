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

/** Mensagens de contexto enviadas ao prompt — janela deliberada (§18 F15). */
const val MAX_HISTORY_MESSAGES = 6

/** Caracteres máximos por mensagem ao serializar o histórico (§18 F15). */
const val MAX_HISTORY_CHARS = 500

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
 * Conversa anterior serializada para o prompt. Mantém no máximo
 * [MAX_HISTORY_MESSAGES] mensagens, cada uma cortada em [MAX_HISTORY_CHARS].
 * Puro/testável.
 */
fun recentHistory(turns: List<ChatTurn>, limit: Int = MAX_HISTORY_MESSAGES): String {
    val recent = turns.filter { it.text.isNotBlank() }.takeLast(limit)
    if (recent.isEmpty()) return ""
    val lines = recent.map { t ->
        val who = if (t.fromMe) "Usuário" else "Copilot"
        val text = if (t.text.length > MAX_HISTORY_CHARS)
            t.text.take(MAX_HISTORY_CHARS) + "…" else t.text
        "$who: $text"
    }
    return "--- CONVERSA ANTERIOR (contexto; continue a partir dela) ---\n" +
        lines.joinToString("\n") + "\n--- FIM DA CONVERSA ANTERIOR ---\n"
}

/**
 * Texto do "brief" da conversa: histórico + continuidade + a mensagem atual.
 * Sem histórico a vira só a mensagem, então a primeira pergunta não carrega
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
    }
    parts += "Mensagem do usuário: \"$message\""
    return parts.joinToString("\n\n")
}

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
 */
fun contextLabel(blockTitle: String?, speechTitle: String?): String {
    val b = blockTitle?.trim().orEmpty()
    if (b.isNotEmpty()) return b
    val s = speechTitle?.trim().orEmpty()
    if (s.isNotEmpty()) return s
    return "sem contexto"
}

/** Aviso offline. `\n` vira quebra de linha na UI. Puro/testável. */
const val OFFLINE_CHAT_NOTICE =
    "Você está offline.\nO Copilot remoto não está disponível, mas os recursos locais continuam funcionando."

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
    data object Offline : ChatRunState
    data class Cancelled(val message: String = friendlyChatError(ProviderErrorCode.CANCELLED)) : ChatRunState

    /** Só impede novo envio durante o turno em andamento. */
    val blocksComposer: Boolean get() = this is Sending || this is Generating

    val isTerminal: Boolean get() = this is Success || this is Error || this is Cancelled
}
