package com.bettertalker.app.data.cloud

/**
 * M5: as cópias de conflito criadas pelo SyncWorker terminam com o sufixo
 * definido no worker. Puro/testável para a UI de conta listar/contar.
 */
fun isConflictNoteTitle(title: String): Boolean =
    title.trimEnd().endsWith(" (conflito)", ignoreCase = true)
