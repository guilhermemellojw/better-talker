package com.bettertalker.app.data.work

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore

/** Cria a porta remota só no flavor syncBeta. */
internal fun createSyncRemote(
    @Suppress("UNUSED_PARAMETER") ctx: Context,
    uid: String,
): SyncRemote? = runCatching {
    FirestoreSyncRemote(FirebaseFirestore.getInstance().collection("users").document(uid))
}.getOrNull()
