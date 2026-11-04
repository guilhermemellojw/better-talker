package com.bettertalker.app.data.cloud

/** Identidade mínima de nuvem usada pela UI/sync, sem vazar tipos do Firebase. */
data class CloudUser(
    val uid: String,
    val email: String?,
)
