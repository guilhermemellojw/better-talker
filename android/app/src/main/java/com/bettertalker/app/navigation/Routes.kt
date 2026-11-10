package com.bettertalker.app.navigation

object Routes {
    /** Aba inicial do v4.0: assistente da nota atual/recentes. */
    const val ASSISTANT = "assistant"
    /** Meus esboços com intenção (manage/assistant/prepare/train/present). */
    const val HOME = "home?intent={intent}"
    const val EDITOR = "editor/{noteId}"
    const val LIBRARY = "library?linkNote={linkNote}"
    /** Aba Preparo: editor root da última nota (sem back arrow). */
    const val PREPARE = "prepare"
    const val TRASH = "trash"
    const val ACCOUNT = "account"
    const val CHAT = "chat/{noteId}"
    const val MODEL = "model"
    const val SETTINGS = "settings"
    /** Tela de Voz (TTS + STT no aparelho). */
    const val VOICE = "voice"
    /** Tela de Aparência (tema + paleta com miniaturas). */
    const val APPEARANCE = "appearance"
    /** Backup local (ZIP/JSON) e restauração. */
    const val BACKUP = "backup"
    /** Dados e armazenamento (Backup, Biblioteca e uso). */
    const val DATA_STORAGE = "data_storage"
    /** #20-fix-b: a Tribuna — mapa de memória do discurso. */
    const val TRIBUNA = "tribuna/{noteId}"
    /** Treino — preparação + leitor em tela cheia (imersivo). */
    const val STAGE = "stage/{noteId}"
    /** Hub "Treino": escolha visual da nota antes da preparação. */
    const val TRAIN_HOME = "train"
    /** Hub "Apresentar": escolha visual da nota antes da Tribuna. */
    const val PRESENT_HOME = "present"
    /** Apresentar live — pré-voo + palco read-only (imersivo). */
    const val PRESENT = "present/{noteId}"
    /** v5.0: primeira abertura guiada (replay opcional para “Rever passos”). */
    const val ONBOARDING = "onboarding?replay={replay}"
    fun editor(noteId: String) = "editor/$noteId"
    fun home(intent: HomeIntent = HomeIntent.MANAGE) =
        "home?intent=${intent.name.lowercase()}"
    fun chat(noteId: String) = "chat/$noteId"
    fun tribuna(noteId: String) = "tribuna/$noteId"
    fun stage(noteId: String) = "stage/$noteId"
    fun present(noteId: String) = "present/$noteId"
    fun onboarding(replay: Boolean = false) = "onboarding?replay=$replay"
    fun library(linkNote: String? = null) =
        "library?linkNote=${linkNote.orEmpty()}"
}

/** Aba da barra global. Puro/testável. */
enum class BottomTab { ASSISTANT, LIBRARY, PREPARE, TRAIN, PRESENT }

/**
 * Aba ativa pela rota atual: Assistente, Biblioteca, Preparo, Treino e
 * Palco. Meus esboços (HOME), editor, Tribuna/Mapa mental e Ensaio/Palco ao
 * vivo não têm aba. Puro/testável.
 */
fun activeTab(route: String?): BottomTab? = when (route) {
    Routes.ASSISTANT -> BottomTab.ASSISTANT
    Routes.LIBRARY -> BottomTab.LIBRARY
    Routes.PREPARE -> BottomTab.PREPARE
    Routes.TRAIN_HOME -> BottomTab.TRAIN
    Routes.PRESENT_HOME -> BottomTab.PRESENT
    else -> null
}

/**
 * Barra de navegação global: Assistente, Biblioteca, Preparo, hubs
 * (Treinar/Palco). Meus esboços, editor, Tribuna/Mapa mental, Ensaio e Palco
 * ao vivo são imersivos ou têm navegação própria. Puro/testável.
 */
fun shouldShowGlobalBar(route: String?): Boolean =
    route == Routes.ASSISTANT || route == Routes.LIBRARY ||
        route == Routes.PREPARE || route == Routes.TRAIN_HOME ||
        route == Routes.PRESENT_HOME

/**
 * Destino restaurado do assistente: só vale se a nota ainda existe e não
 * está na lixeira. Puro/testável (sem tocar no Room).
 */
fun resolveAssistantNoteId(lastNoteId: String?, availableIds: Set<String>): String? =
    lastNoteId?.takeIf { it in availableIds }
