package com.bettertalker.app.data.images

import com.google.android.gms.tasks.Task
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 5.4.1 — Storage remoto uid-scoped.
 *
 * Upload grava customMetadata (noteId, sectionId, caption, kind, order),
 * list/download permitem restaurar em outro aparelho. Sem URL pública.
 */
class FirebaseImageUploader : ImageCloudRemote {

    override suspend fun upload(
        path: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ) {
        require(bytes.isNotEmpty()) { "imagem vazia" }
        val ref = FirebaseStorage.getInstance().reference.child(path)
        val builder = StorageMetadata.Builder().setContentType(contentType)
        metadata.forEach { (key, value) -> builder.setCustomMetadata(key, value) }
        await(ref.putBytes(bytes, builder.build()))
    }

    override suspend fun list(prefix: String): List<CloudImageObject> =
        withContext(Dispatchers.IO) {
            listTree(FirebaseStorage.getInstance().reference.child(prefix))
        }

    override suspend fun download(path: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                await(
                    FirebaseStorage.getInstance().reference
                        .child(path)
                        .getBytes(MAX_DOWNLOAD_BYTES)
                )
            }.getOrNull()
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

    private suspend fun listTree(ref: StorageReference): List<CloudImageObject> {
        val list = await(ref.listAll())
        val out = mutableListOf<CloudImageObject>()
        for (item in list.items) {
            val meta = runCatching { await(item.metadata) }.getOrNull()
            out += CloudImageObject(
                path = item.path.trimStart('/'),
                sizeBytes = meta?.sizeBytes ?: 0L,
                contentType = meta?.contentType,
                metadata = customMetadata(meta),
            )
        }
        for (child in list.prefixes) out += listTree(child)
        return out
    }

    private fun customMetadata(meta: StorageMetadata?): Map<String, String> {
        if (meta == null) return emptyMap()
        return meta.customMetadataKeys.mapNotNull { key ->
            meta.getCustomMetadata(key)?.let { key to it }
        }.toMap()
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

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024 + 1
    }
}
