package com.bettertalker.app.data.cloud

/** Puro/testável: troca de conta bloqueada pelo guard de 1 conta/aparelho. */
internal fun accountSwitchBlocked(lastUid: String?, newUid: String?): Boolean =
    lastUid != null && newUid != null && lastUid != newUid
