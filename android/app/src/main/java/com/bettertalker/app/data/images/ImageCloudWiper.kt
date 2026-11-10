package com.bettertalker.app.data.images

import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ListResult
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 5.4 — apaga o backup de imagens da conta no Storage.
 *
 * O caminho é sempre `users/{uid}/images/...`; nada fora da árvore do uid é
 * tocado. O original local continua no aparelho.
 */
object ImageCloudWiper {

    suspend fun wipe(uid: String): String? = try {
        deleteTree(FirebaseStorage.getInstance().reference.child("users/$uid/images"))
        null
    } catch (e: Exception) {
        e.message?.take(160) ?: "Falha ao apagar o backup de imagens."
    }

    private suspend fun deleteTree(ref: StorageReference) {
        val list: ListResult = awaitTask { cb ->
            ref.listAll().addOnCompleteListener(cb)
        }
        for (item in list.items) {
            runCatching {
                awaitTask<Void> { cb -> item.delete().addOnCompleteListener(cb) }
            }
        }
        for (prefix in list.prefixes) deleteTree(prefix)
    }

    private suspend fun <T> awaitTask(
        block: (com.google.android.gms.tasks.OnCompleteListener<T>) -> Unit
    ): T = suspendCancellableCoroutine { cont ->
        block(com.google.android.gms.tasks.OnCompleteListener { task ->
            if (task.isCanceled) cont.cancel()
            else if (task.isSuccessful) {
                @Suppress("UNCHECKED_CAST")
                cont.resume(task.result as T) {}
            } else {
                cont.resumeWithException(task.exception ?: Exception("Firebase falhou"))
            }
        })
    }
}
