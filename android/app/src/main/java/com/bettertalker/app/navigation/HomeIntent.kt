package com.bettertalker.app.navigation

/** 5.5 — uma tela "Meus esboços", várias intenções de entrada. */
enum class HomeIntent {
    MANAGE, ASSISTANT, PREPARE, TRAIN, PRESENT;

    val isSelection: Boolean get() = this != MANAGE

    fun selectionLabel(): String? = when (this) {
        MANAGE -> null
        ASSISTANT -> "Escolha o esboço do Assistente"
        PREPARE -> "Escolha o esboço do Preparo"
        TRAIN -> "Escolha o esboço do Treino"
        PRESENT -> "Escolha o esboço do Palco"
    }

    /** Modo gerenciar sempre abre o Preparo; seleção vincula ao chamador. */
    fun tapAction(manageMode: Boolean): HomeTapAction =
        if (manageMode || this == MANAGE) HomeTapAction.OPEN_PREPARE
        else HomeTapAction.SELECT
}

enum class HomeTapAction { OPEN_PREPARE, SELECT }

fun homeIntentFromRoute(raw: String?): HomeIntent =
    HomeIntent.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: HomeIntent.MANAGE
