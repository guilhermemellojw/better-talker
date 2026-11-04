package com.bettertalker.app.data.storage

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 3.3 — armazenamento canônico das publicações.
 *
 * - [internalDir] (`filesDir/publicacoes`) é o único acervo: importação manual,
 *   restauração de missing e download via WebView terminam aqui.
 * - [stagingDir] (`Android/data/<pkg>/files/downloads_tmp`) é só a área externa
 *   temporária que o DownloadManager exige. O worker copia para o interno e
 *   remove staging + registro do DownloadManager.
 */
object PublicationStorage {
    const val INTERNAL_DIR = "publicacoes"
    const val STAGING_DIR = "downloads_tmp"
    private const val STAGING_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    fun internalDir(ctx: Context): File =
        File(ctx.filesDir, INTERNAL_DIR).apply { mkdirs() }

    fun stagingDir(ctx: Context): File =
        (ctx.getExternalFilesDir(STAGING_DIR) ?: File(ctx.cacheDir, STAGING_DIR))
            .apply { mkdirs() }

    /** Nome único só para o destino do DownloadManager. */
    fun stagingName(displayName: String): String =
        "${UUID.randomUUID()}-$displayName"

    fun internalFile(ctx: Context, id: String, displayName: String): File {
        val safe = displayName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").takeLast(80)
        return File(internalDir(ctx), "$id-$safe")
    }

    /** Temp interno do worker; nunca vira anexo. */
    fun workerTemp(ctx: Context, placeholderId: String): File =
        File(ctx.cacheDir, "dl-$placeholderId.tmp")

    /** Remove staging órfão/antigo. Nunca toca no acervo interno. */
    fun cleanupStaging(ctx: Context, now: Long = System.currentTimeMillis()) {
        val cutoff = now - STAGING_MAX_AGE_MS
        runCatching {
            stagingDir(ctx).listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }
}
