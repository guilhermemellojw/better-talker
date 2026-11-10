package com.bettertalker.app.data.images

/**
 * 5.4 — regras puras do backup de imagens.
 *
 * O binário local nunca é alterado; só o que sobe é normalizado. GIF pequeno
 * mantém a animação; GIF grande vira WebP estático (primeiro quadro).
 */
object ImageBackupPolicy {

    /** Limite por arquivo de origem (fotos de celular costumam ter 3–8 MB). */
    const val MAX_IMAGE_BYTES: Long = 10L * 1024 * 1024

    /** Acima disso o GIF perde a animação e vira WebP. */
    const val MAX_GIF_BYTES: Long = 2L * 1024 * 1024

    /** Lado maior da imagem enviada; o original local fica intacto. */
    const val MAX_DIMENSION: Int = 2048

    const val WEBP_QUALITY: Int = 80

    fun isGif(mime: String): Boolean = mime.equals("image/gif", ignoreCase = true)

    fun isWebp(mime: String): Boolean = mime.equals("image/webp", ignoreCase = true)

    fun isUploadable(mime: String, bytes: Long): Boolean =
        mime.startsWith("image/", ignoreCase = true) &&
            bytes in 1..MAX_IMAGE_BYTES

    /** Mantém os bytes originais quando já estão no formato final e são pequenos. */
    fun keepOriginal(mime: String, bytes: Long): Boolean =
        bytes in 1..MAX_GIF_BYTES && (isGif(mime) || isWebp(mime))

    fun extensionFor(mime: String, bytes: Long): String = when {
        keepOriginal(mime, bytes) && isGif(mime) -> "gif"
        keepOriginal(mime, bytes) && isWebp(mime) -> "webp"
        else -> "webp"
    }

    fun contentTypeFor(mime: String, bytes: Long): String = when {
        keepOriginal(mime, bytes) && isGif(mime) -> "image/gif"
        keepOriginal(mime, bytes) && isWebp(mime) -> "image/webp"
        else -> "image/webp"
    }

    fun remotePath(uid: String, noteId: String, imageId: String, ext: String): String =
        "users/$uid/images/$noteId/$imageId.$ext"
}
