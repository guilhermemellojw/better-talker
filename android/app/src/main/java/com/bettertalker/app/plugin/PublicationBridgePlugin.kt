package com.bettertalker.app.plugin

import android.content.Intent
import android.net.Uri
import android.webkit.DownloadListener
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File

@CapacitorPlugin(name = "PublicationBridge")
class PublicationBridgePlugin : Plugin() {

    private var webViewDialog: DownloadWebViewDialog? = null

    @PluginMethod
    fun abrirNavegadorParaDownload(call: PluginCall) {
        val url = call.getString("url") ?: run {
            call.reject("URL obrigatória")
            return
        }

        webViewDialog = DownloadWebViewDialog.newInstance(url)
        webViewDialog?.show(bridge.context.supportFragmentManager, "downloadWebView")

        call.resolve()
    }

    @PluginMethod
    fun cancelDownload(call: PluginCall) {
        webViewDialog?.dismiss()
        webViewDialog = null
        call.resolve()
    }

    override fun load() {
        // Plugin loaded
    }
}
