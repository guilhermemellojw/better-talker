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
    fun editor(noteId: String) = "editor/$noteId"
    fun chat(noteId: String) = "chat/$noteId"
    fun tribuna(noteId: String) = "tribuna/$noteId"
    fun library(linkNote: String? = null) =
        if (linkNote == null) "library?linkNote=" else "library?linkNote=$linkNote"
}
