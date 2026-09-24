package com.bettertalker.app.ui.jw

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.URLUtil
import androidx.core.content.ContextCompat

/** DownloadManager compartilhado (WebView interna e download direto da API). */
object DownloadHelper {

    /** URL de página (reabre WebView) vs arquivo direto (re-enfileira DM). */
    fun isPageUrl(url: String): Boolean =
        com.bettertalker.app.data.util.isPageUrl(url)

    fun hasNotifPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Garante extensão a partir do MIME quando o nome adivinhado vem sem
     * (páginas/ APIs do site). Sem isso o placeholder é descartado em
     * silêncio e o arquivo "nunca é indexado". Puro/testável.
     */
    fun ensureExtension(name: String, mime: String?): String {
        if (name.contains('.')) return name
        val ext = when {
            mime?.contains("pdf", ignoreCase = true) == true -> "pdf"
            mime?.contains("epub", ignoreCase = true) == true -> "epub"
            mime?.contains("officedocument.wordprocessingml", ignoreCase = true) == true -> "docx"
            mime?.contains("rtf", ignoreCase = true) == true -> "rtf"
            mime?.contains("zip", ignoreCase = true) == true -> "zip"
            mime?.contains("text", ignoreCase = true) == true -> "txt"
            else -> return name
        }
        return "$name.$ext"
    }

    /**
     * Enfileira o download em Downloads e retorna a referência do
     * DownloadManager (null se falhar). Não mostra Toast (chamador decide).
     */
    fun enqueue(context: Context, url: String, fileName: String): Long? {
        return try {
            val name = fileName.ifBlank {
                URLUtil.guessFileName(url, null, null).ifBlank { "download.bin" }
            }
            val req = DownloadManager.Request(Uri.parse(url))
                .setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE
                )
                .setAllowedOverRoaming(false)
                .setTitle("Better Talker")
                .setDescription("Baixando $name")
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setNotificationVisibility(
                    if (hasNotifPermission(context)) DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    else DownloadManager.Request.VISIBILITY_VISIBLE
                )
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
        } catch (_: Exception) {
            null
        }
    }
}
