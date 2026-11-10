package com.bettertalker.app.data.images

import com.google.android.gms.tasks.Task
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 5.4 — upload uid-scoped no Firebase Storage.
 *
 * O caminho é montado por [ImageBackupPolicy.remotePath]; não há URL pública
 * e o Palco continua lendo o arquivo local. Objetos ficam em
 * `users/{uid}/images/...` e as rules exigem `request.auth.uid == uid`.
 */
class FirebaseImageUploader : ImageUploader {

    override suspend fun upload(path: String, bytes: ByteArray, contentType: String) {
        require(bytes.isNotEmpty()) { "imagem vazia" }
        val ref = FirebaseStorage.getInstance().reference.child(path)
        val metadata = StorageMetadata.Builder()
            .setContentType(contentType)
            .build()
        await(ref.putBytes(bytes, metadata))
    }

    override suspend fun delete(path: String) {
        try {
            val ref = FirebaseStorage.getInstance().reference.child(path)
            await(ref.delete())
        } catch (e: Exception) {
            if (e !is StorageException || e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) {
                throw e
            }
        }
    }

    private suspend fun <T> await(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnCompleteListener { result ->
            if (result.isCanceled) cont.cancel()
            else if (result.isSuccessful) {
                @Suppress("UNCHECKED_CAST")
                cont.resume(result.result as T) {}
            } else {
                cont.resumeWithException(result.exception ?: Exception("Firebase falhou"))
            }
        }
    }
}
