package com.bettertalker.app.data.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * T1 (Exportação) — nome-base seguro para arquivo compartilhado e job name do
 * PDF: remove os caracteres que quebram o picker do Android (`º ª / \ : * ? "
 * < > |`) e mantém o resto legível — "N.º 28 — Tema" vira "N 28 — Tema".
 * Nunca vira hash. Puro/testável.
 */
fun exportFileBaseName(title: String): String = title
    .replace(Regex("[ºª/:\\\\*?\"<>|]"), " ")
    // Ponto colado em espaço ("N. ") não vira "extensão" no picker do Samsung.
    .replace(Regex("\\.\\s+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { "Discurso" }
    .take(60)

/**
 * T3 (Exportação) — "Salvar PDF" via `PrintManager` + WebView offscreen.
 *
 * Nativo, sem bibliotecas: o WebView carrega o HTML do [buildExportHtml]
 * (CSS de impressão embutido) e o `PrintManager` abre o diálogo do sistema —
 * o usuário escolhe "Salvar como PDF" e o destino (SAF). Zero permissões.
 *
 * O WebView NÃO é anexado à UI; fica referenciado aqui até a próxima
 * impressão para o GC não matá-lo no meio do job.
 */
object PdfExportManager {

    /** Referência viva do último WebView de impressão (anti-GC). */
    @Volatile
    private var printingWebView: WebView? = null

    /**
     * Abre o diálogo de impressão do sistema para o discurso.
     * Chamar da MAIN thread (WebView); usa o contexto da Activity no print.
     */
    fun printDiscurso(context: Context, jobName: String, html: String) {
        // Fix: nome sanitizado (o picker do Samsung truncava em "º").
        val name = exportFileBaseName(jobName)
        Handler(Looper.getMainLooper()).post {
            val app = context.applicationContext
            // Job anterior já foi resolvido pelo sistema; libera o WebView antigo.
            printingWebView?.destroy()
            val webView = WebView(app).apply {
                settings.javaScriptEnabled = false
                // A1: impressão é offline e não abre arquivos/conteúdo externo.
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val printManager =
                            context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        val adapter = view.createPrintDocumentAdapter(name)
                        // Margens controladas pelo @page do HTML (T1).
                        val attrs = PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build()
                        printManager.print(name, adapter, attrs)
                    }
                }
            }
            printingWebView = webView
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }
}
