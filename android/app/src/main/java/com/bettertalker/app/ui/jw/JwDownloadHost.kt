package com.bettertalker.app.ui.jw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.fragment.app.FragmentActivity

@Composable
fun JwDownloadDialog(
    url: String,
    onDismiss: () -> Unit,
    onDownloaded: ((fileName: String, absPath: String) -> Unit)? = null
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? FragmentActivity ?: return
    DisposableEffect(url) {
        val dlg = JwDownloadDialog.newInstance(url)
        val prev = JwDownloadDialog.onDownloaded
        if (onDownloaded != null) {
            val outer = onDownloaded
            val chained = prev
            JwDownloadDialog.onDownloaded = { name, path ->
                runCatching { chained?.invoke(name, path) }
                outer(name, path)
            }
        }
        dlg.show(activity.supportFragmentManager, "jw")
        onDispose {
            runCatching { dlg.dismiss() }
            onDismiss()
        }
    }
}
