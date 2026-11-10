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

/** Porta de upload: produção usa Firebase Storage; testes usam um fake. */
interface ImageUploader {
    suspend fun upload(path: String, bytes: ByteArray, contentType: String)
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
    val budgetWarning: String? get() = ImageBackupPolicy.budgetWarning(uploadedBytes)
}
