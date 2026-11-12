package com.bettertalker.app.domain.help

/** Origem da ajuda exibida. */
enum class HelpSource { FIRST_RUN, HELP_BUTTON, TRIGGER, ERROR_ACTION, HELP_REOPEN }

/** Erro conhecido que pode contextualizar a ajuda. */
enum class HelpErrorKind { NO_KEY, MISSING_SOURCES, GENERATION, ANALYSIS, OTHER }

/** Ações permitidas para o BT de ajuda. Nada aqui edita conteúdo sem toque. */
enum class HelpAction {
    OPEN_IMPORT,
    OPEN_PASTE,
    OPEN_CREATE,
    OPEN_EDITOR,
    OPEN_LIBRARY,
    OPEN_MODEL,
    OPEN_TRAIN,
    OPEN_PRESENT,
    OPEN_TRIBUNA,
    OPEN_VOICE,
    OPEN_ASSISTANT,
    OPEN_ACCOUNT,
    OPEN_DATA_STORAGE,
    OPEN_HELP_TOPIC,
    NONE,
}

/** Snapshot mínimo da prática atual; campos explícitos, sem mapa genérico. */
data class PracticeSnapshot(
    val mode: String? = null,
    val scopeLabel: String? = null,
    val readiness: String? = null,
)

/**
 * Contexto local da ajuda. Títulos servem só para exibição local; nunca são
 * enviados automaticamente ao LLM.
 */
data class AssistantHelpContext(
    val route: String,
    val noteId: String? = null,
    val noteTitle: String? = null,
    val sectionId: String? = null,
    val sectionTitle: String? = null,
    val practice: PracticeSnapshot? = null,
    val errorKind: HelpErrorKind? = null,
    val source: HelpSource = HelpSource.HELP_BUTTON,
)

/** Um tópico do catálogo local. */
data class HelpTopic(
    val id: String,
    val question: String,
    val shortAnswer: String,
    val steps: List<String>,
    val action: HelpAction,
    val keywords: List<String> = emptyList(),
    /** Rotas em que o tópico é mais relevante; vazio = tópico geral. */
    val routes: Set<String> = emptySet(),
    /** A ação precisa de uma nota atual; sem ela o handler usa o picker. */
    val requiresNote: Boolean = false,
)

/** Gatilhos proativos mínimos. `STAGE_TRIBUNA` é reservado e não duplicado. */
enum class HelpTriggerId(val priority: Int) {
    NO_KEY(1),
    MISSING_SOURCES(2),
    EMPTY_LIBRARY(3),
    ERROR_HELP(4),
    STAGE_TRIBUNA(99),
}

/** Estado persistido de um gatilho. */
data class HelpTriggerState(
    val seen: Boolean = false,
    val dismissed: Boolean = false,
    val lastShownAt: Long = 0L,
)

/** Decisão pronta para a UI. */
data class HelpTriggerDecision(
    val id: HelpTriggerId,
    val message: String,
    val action: HelpAction,
)

/** Cooldown padrão ao apenas ignorar/fechar o gatilho. */
const val HELP_TRIGGER_COOLDOWN_MS: Long = 7L * 24 * 60 * 60 * 1000
