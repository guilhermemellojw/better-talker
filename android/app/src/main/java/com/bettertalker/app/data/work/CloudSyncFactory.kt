package com.bettertalker.app.data.work

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore

/** 5.8: cria a porta remota Firestore nos dois flavors. */
internal fun createSyncRemote(
    @Suppress("UNUSED_PARAMETER") ctx: Context,
    uid: String,
): SyncRemote? = runCatching {
    FirestoreSyncRemote(FirebaseFirestore.getInstance().collection("users").document(uid))
}.getOrNull()
