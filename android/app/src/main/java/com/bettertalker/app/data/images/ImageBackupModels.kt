package com.bettertalker.app.data.images

import java.io.File

/** Bytes que realmente sobem para o Storage (nunca o original local). */
data class CompressedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
)

/** Porta de compressão: JVM tests usam um fake; produção usa BitmapFactory. */
fun interface ImageCompressor {
    fun compress(file: File, mime: String): CompressedImage?
}

/** Objeto remoto listado no Storage (tamanho/metadata para o restore). */
data class CloudImageObject(
    val path: String,
    val sizeBytes: Long = 0L,
    val contentType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * 5.4.1 — porta do Storage. Produção usa Firebase; testes usam um fake.
 * O upload grava customMetadata (noteId, sectionId, caption, kind, order).
 */
interface ImageCloudRemote {
    suspend fun upload(
        path: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String> = emptyMap(),
    )
    suspend fun list(prefix: String): List<CloudImageObject>
    suspend fun download(path: String): ByteArray?
    suspend fun delete(path: String)
}

sealed interface ImageBackupOutcome {
    data class Uploaded(val cloudPath: String, val bytes: Long) : ImageBackupOutcome
    data class Failed(val message: String, val retryable: Boolean) : ImageBackupOutcome
    data object Deferred : ImageBackupOutcome
    data object Missing : ImageBackupOutcome
}

data class ImageBackupStats(
    val total: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val uploadedBytes: Long = 0L,
    val lastError: String? = null,
) {
    val pending: Int get() = (total - uploaded).coerceAtLeast(0)
}

/** Resultado de uma passada de restore (idempotente). */
data class ImageRestoreReport(
    val cloudTotal: Int = 0,
    val restored: Int = 0,
    val alreadyLocal: Int = 0,
    val missingNote: Int = 0,
    val failed: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val summary: String
        get() = "$cloudTotal na nuvem · $missingNote sem nota local · " +
            "$restored restaurada(s) · $alreadyLocal já local(is)" +
            if (failed > 0) " · $failed falha(s)" else ""
}
