package com.bettertalker.app.data.work

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.SetOptions

/**
 * F7a — implementação de produção do [SyncRemote] sobre o SDK do Firestore.
 *
 * `root` é o documento `users/{uid}` que o SyncWorker já usava; o contrato
 * espelha 1:1 as chamadas anteriores (`collection(name).get()`,
 * `document(id).get()`, `document(id).set(map, SetOptions.merge())`,
 * `document(id).delete()`), sem mudança de comportamento.
 */
class FirestoreSyncRemote(private val root: DocumentReference) : SyncRemote {

    override suspend fun getAll(collection: String): Map<String, Map<String, Any?>> =
        root.collection(collection).get().await().documents
            .associate { it.id to (it.data ?: emptyMap()) }

    override suspend fun get(collection: String, id: String): Map<String, Any?>? =
        root.collection(collection).document(id).get().await().data

    override suspend fun set(collection: String, id: String, data: Map<String, Any?>) {
        root.collection(collection).document(id).set(data, SetOptions.merge()).await()
    }

    override suspend fun delete(collection: String, id: String) {
        root.collection(collection).document(id).delete().await()
    }

    /**
     * Heartbeat em `users/{uid}/_sync/clock`: grava um serverTimestamp e lê
     * de volta o valor resolvido. Custa 1 write + 1 read por sync.
     */
    override suspend fun serverNow(): Long? {
        val ref = root.collection("_sync").document("clock")
        ref.set(
            mapOf("at" to com.google.firebase.firestore.FieldValue.serverTimestamp()),
            SetOptions.merge(),
        ).await()
        val data = ref.get().await().data ?: return null
        return (data["at"] as? com.google.firebase.Timestamp)?.toDate()?.time
    }
}

/** Await de Task do Play Services (mesma implementação que o worker usava). */
internal suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    com.bettertalker.app.data.cloud.AuthRepository.awaitTask { this.addOnCompleteListener(it) }
