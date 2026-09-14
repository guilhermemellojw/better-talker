package com.bettertalker.app.plugin

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.bettertalker.app.R
import java.io.File

class DownloadWebViewDialog : DialogFragment() {

    private var webView: WebView? = null
    private var targetUrl: String = ""

    companion object {
        private const val ARG_URL = "url"

        fun newInstance(url: String): DownloadWebViewDialog {
            val fragment = DownloadWebViewDialog()
            val args = Bundle()
            args.putString(ARG_URL, url)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_NoActionBar)
        targetUrl = arguments?.getString(ARG_URL) ?: return
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.setContentView(R.layout.download_webview)
        dialog.setCancelable(true)

        webView = dialog.findViewById(R.id.downloadWebView)
        webView?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val dir = File(context.filesDir, "publicacoes")
                if (!dir.exists()) dir.mkdirs()
                val outputFile = File(dir, fileName)

                try {
                    val request = android.app.DownloadManager.Request(Uri.parse(url))
                        .setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI or android.app.DownloadManager.Request.NETWORK_MOBILE)
                        .setAllowedOverRoaming(false)
                        .setTitle("Better Talker - Download")
                        .setDescription("Baixando publicação...")
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    val reference = dm.enqueue(request)

                    Toast.makeText(context, "Download iniciado: $fileName", Toast.LENGTH_LONG).show()
                    notifyJsDownloadComplete(context, fileName, outputFile.absolutePath)
                } catch (e: Exception) {
                    Toast.makeText(context, "Erro ao iniciar download: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        webView?.loadUrl(targetUrl)

        dialog.setOnCancelListener {
            webView?.destroy()
            webView = null
        }

        return dialog
    }

    private fun notifyJsDownloadComplete(context: Context, fileName: String, path: String) {
        val js = """window.onPublicacaoBaixada('ok','$path')"""
        requireActivity().runOnUiThread {
            webView?.post {
                webView?.evaluateJavascript(js, null)
            }
        }
    }

    override fun onDestroyView() {
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }
}
