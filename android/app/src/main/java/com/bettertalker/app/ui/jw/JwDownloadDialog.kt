package com.bettertalker.app.ui.jw

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.bettertalker.app.R

/**
 * BYOD: abre o site oficial numa WebView interna com topbar
 * (voltar, avançar, título, progresso, recarregar, fechar).
 * O usuário baixa ele mesmo; o app só registra o arquivo local.
 */
class JwDownloadDialog : DialogFragment() {
    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TAG = "tag"
        /** (downloadManagerId, fileName) — o registro é feito por worker (sobrevive à rotação). */
        var onEnqueued: ((downloadId: Long, fileName: String) -> Unit)? = null
        fun resultKey(tag: String) = "jw_dismiss_$tag"
        fun newInstance(url: String, tag: String) = JwDownloadDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, url)
                putString(ARG_TAG, tag)
            }
        }
    }

    private var webView: WebView? = null
    private var btnBack: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var titleView: TextView? = null
    private var progressBar: ProgressBar? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val url = arguments?.getString(ARG_URL).orEmpty()
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.setContentView(R.layout.download_webview)
        dialog.setCancelable(true)
        webView = dialog.findViewById(R.id.downloadWebView)
        btnBack = dialog.findViewById(R.id.jwBack)
        btnForward = dialog.findViewById(R.id.jwForward)
        titleView = dialog.findViewById(R.id.jwTitle)
        progressBar = dialog.findViewById(R.id.jwProgress)
        dialog.findViewById<View>(R.id.jwClose).setOnClickListener { dismiss() }
        dialog.findViewById<View>(R.id.jwRefresh).setOnClickListener { webView?.reload() }
        btnBack?.setOnClickListener { if (webView?.canGoBack() == true) webView?.goBack() }
        btnForward?.setOnClickListener { if (webView?.canGoForward() == true) webView?.goForward() }
        // botão voltar do sistema: navega no histórico antes de fechar
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        webView?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    updateNav()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    titleView?.text = title?.ifBlank { "jw.org" } ?: "jw.org"
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    progressBar?.apply {
                        progress = newProgress
                        visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    }
                    if (newProgress == 100) updateNav()
                }
            }
            setDownloadListener(DownloadListener { dlUrl, _, disposition, mime, _ ->
                try {
                    val fileName = URLUtil.guessFileName(dlUrl, disposition, mime)
                    val reference = DownloadHelper.enqueue(context, dlUrl, fileName)
                    if (reference == null) {
                        Toast.makeText(context, "Não foi possível iniciar o download.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Download iniciado: $fileName", Toast.LENGTH_LONG).show()
                        onEnqueued?.invoke(reference, fileName)
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            })
        }
        if (webView?.url.isNullOrEmpty()) webView?.loadUrl(url)
        updateNav()
        dialog.setOnCancelListener { webView?.destroy(); webView = null }
        return dialog
    }

    private fun updateNav() {
        btnBack?.isEnabled = webView?.canGoBack() == true
        btnBack?.alpha = if (webView?.canGoBack() == true) 1f else 0.35f
        btnForward?.isEnabled = webView?.canGoForward() == true
        btnForward?.alpha = if (webView?.canGoForward() == true) 1f else 0.35f
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // avisa o host mesmo quando fecha pelo botão voltar do sistema:
        // sem isso o estado Compose trava e o diálogo não reabre
        runCatching {
            val tag = arguments?.getString(ARG_TAG).orEmpty()
            parentFragmentManager.setFragmentResult(resultKey(tag), Bundle.EMPTY)
        }
    }

    override fun onDestroyView() { webView?.destroy(); webView = null; super.onDestroyView() }
}
