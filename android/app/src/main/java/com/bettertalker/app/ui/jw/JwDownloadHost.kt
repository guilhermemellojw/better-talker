package com.bettertalker.app.ui.jw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.fragment.app.FragmentActivity

@Composable
fun JwDownloadDialog(url: String, onDismiss: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? FragmentActivity ?: return
    DisposableEffect(url) {
        val tag = "jw-${url.hashCode()}-${System.currentTimeMillis()}"
        val dlg = JwDownloadDialog.newInstance(url, tag)
        dlg.show(activity.supportFragmentManager, tag)
        onDispose {
            runCatching { dlg.dismiss() }
            onDismiss()
        }
    }
}
