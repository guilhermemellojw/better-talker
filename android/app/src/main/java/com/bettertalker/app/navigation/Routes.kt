package com.bettertalker.app.navigation

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{noteId}"
    const val LIBRARY = "library?linkNote={linkNote}"
    const val TRASH = "trash"
    const val ACCOUNT = "account"
    fun editor(noteId: String) = "editor/$noteId"
    fun library(linkNote: String? = null) =
        if (linkNote == null) "library?linkNote=" else "library?linkNote=$linkNote"
}
