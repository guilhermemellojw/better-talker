package com.bettertalker.app.ui.jw

import android.app.Dialog
import android.os.Bundle
import android.os.Environment
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.bettertalker.app.R

private fun hasNotifPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * BYOD: abre o site oficial numa WebView interna. O usuário baixa ele mesmo;
 * o app só registra o arquivo local para indexar. Sem redistribuição.
 */
class JwDownloadDialog : DialogFragment() {
    companion object {
        private const val ARG_URL = "url"
        /** (downloadManagerId, fileName) — o registro é feito por worker (sobrevive à rotação). */
        var onEnqueued: ((downloadId: Long, fileName: String) -> Unit)? = null
        fun newInstance(url: String, tag: String) = JwDownloadDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, url)
                putString("tag", tag)
            }
        }
    }

    private var webView: WebView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val url = arguments?.getString(ARG_URL).orEmpty()
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.setContentView(R.layout.download_webview)
        dialog.setCancelable(true)
        webView = dialog.findViewById(R.id.downloadWebView)
        webView?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setDownloadListener(DownloadListener { dlUrl, _, disposition, mime, _ ->
                try {
                    val fileName = URLUtil.guessFileName(dlUrl, disposition, mime)
                    val req = android.app.DownloadManager.Request(android.net.Uri.parse(dlUrl))
                        .setAllowedNetworkTypes(
                            android.app.DownloadManager.Request.NETWORK_WIFI or
                                android.app.DownloadManager.Request.NETWORK_MOBILE
                        )
                        .setAllowedOverRoaming(false)
                        .setTitle("Better Talker")
                        .setDescription("Baixando $fileName")
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                        .setNotificationVisibility(
                            if (hasNotifPermission(context)) android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            else android.app.DownloadManager.Request.VISIBILITY_VISIBLE
                        )
                    val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    val reference = dm.enqueue(req)
                    Toast.makeText(context, "Download iniciado: $fileName", Toast.LENGTH_LONG).show()
                    onEnqueued?.invoke(reference, fileName)
                } catch (e: Exception) {
                    Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            })
        }
        webView?.loadUrl(url)
        dialog.setOnCancelListener { webView?.destroy(); webView = null }
        return dialog
    }

    override fun onDestroyView() { webView?.destroy(); webView = null; super.onDestroyView() }
}
