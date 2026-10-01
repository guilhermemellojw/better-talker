package com.bettertalker.app.navigation

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{noteId}"
    const val LIBRARY = "library?linkNote={linkNote}"
    const val TRASH = "trash"
    const val ACCOUNT = "account"
    const val CHAT = "chat/{noteId}"
    const val MODEL = "model"
    // SPIKE 3.2.1 — REMOVER
    const val PROTOTYPE_SECTIONS = "prototype_sections"
    fun editor(noteId: String) = "editor/$noteId"
    fun chat(noteId: String) = "chat/$noteId"
    fun library(linkNote: String? = null) =
        if (linkNote == null) "library?linkNote=" else "library?linkNote=$linkNote"
}
