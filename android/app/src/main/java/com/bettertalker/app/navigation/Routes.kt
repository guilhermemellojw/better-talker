package com.bettertalker.app.navigation

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{noteId}"
    const val LIBRARY = "library?linkNote={linkNote}"
    const val TRASH = "trash"
    const val ACCOUNT = "account"
    const val CHAT = "chat/{noteId}"
    const val MODEL = "model"
    const val SETTINGS = "settings"
    /** #20-fix-b: a Tribuna — mapa de memória do discurso. */
    const val TRIBUNA = "tribuna/{noteId}"
    /** Ensaio — leitor de tribuna em tela cheia (imersivo). */
    const val STAGE = "stage/{noteId}"
    fun editor(noteId: String) = "editor/$noteId"
    fun chat(noteId: String) = "chat/$noteId"
    fun tribuna(noteId: String) = "tribuna/$noteId"
    fun stage(noteId: String) = "stage/$noteId"
    fun library(linkNote: String? = null) =
        if (linkNote == null) "library?linkNote=" else "library?linkNote=$linkNote"
}

/**
 * Barra de navegação global: aparece só na Home e na Tribuna — o Ensaio é
 * imersivo (tela cheia, barra escondida) e as demais telas têm navegação
 * própria. Puro/testável.
 */
fun shouldShowGlobalBar(route: String?): Boolean =
    route == Routes.HOME || route == Routes.TRIBUNA
