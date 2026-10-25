package com.bettertalker.app.navigation

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{noteId}"
    const val LIBRARY = "library?linkNote={linkNote}&scan={scan}"
    const val TRASH = "trash"
    const val ACCOUNT = "account"
    const val CHAT = "chat/{noteId}"
    const val MODEL = "model"
    const val SETTINGS = "settings"
    /** Tela de Aparência (tema + paleta com miniaturas). */
    const val APPEARANCE = "appearance"
    /** #20-fix-b: a Tribuna — mapa de memória do discurso. */
    const val TRIBUNA = "tribuna/{noteId}"
    /** Ensaio — leitor de tribuna em tela cheia (imersivo). */
    const val STAGE = "stage/{noteId}"
    /** Hub "Treinar": escolha visual da nota antes do Ensaio. */
    const val TRAIN_HOME = "train"
    /** Hub "Apresentar": escolha visual da nota antes da Tribuna. */
    const val PRESENT_HOME = "present"
    fun editor(noteId: String) = "editor/$noteId"
    fun chat(noteId: String) = "chat/$noteId"
    fun tribuna(noteId: String) = "tribuna/$noteId"
    fun stage(noteId: String) = "stage/$noteId"
    fun library(linkNote: String? = null, scan: Boolean = false) =
        "library?linkNote=${linkNote.orEmpty()}&scan=$scan"
}

/** Aba da barra global. Puro/testável. */
enum class BottomTab { HOME, TRAIN, PRESENT }

/**
 * Aba ativa pela rota atual: hubs destacam a própria aba; a Tribuna destaca
 * "Apresentar"; o Ensaio não aparece (imersivo, sem barra). Puro/testável.
 */
fun activeTab(route: String?): BottomTab? = when (route) {
    Routes.HOME -> BottomTab.HOME
    Routes.TRAIN_HOME -> BottomTab.TRAIN
    Routes.PRESENT_HOME, Routes.TRIBUNA -> BottomTab.PRESENT
    else -> null
}

/**
 * Barra de navegação global: aparece na Home, nos hubs (Treinar/Apresentar)
 * e na Tribuna — o Ensaio é imersivo (tela cheia, barra escondida) e as
 * demais telas têm navegação própria. Puro/testável.
 */
fun shouldShowGlobalBar(route: String?): Boolean =
    route == Routes.HOME || route == Routes.TRIBUNA ||
        route == Routes.TRAIN_HOME || route == Routes.PRESENT_HOME
