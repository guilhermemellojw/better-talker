package com.bettertalker.app.data.work

import android.content.Context

/** Build público não tem backend remoto. */
internal fun createSyncRemote(
    @Suppress("UNUSED_PARAMETER") ctx: Context,
    @Suppress("UNUSED_PARAMETER") uid: String,
): SyncRemote? = null
