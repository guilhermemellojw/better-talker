package com.bettertalker.app.ui.jw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.fragment.app.FragmentActivity

@Composable
fun JwDownloadDialog(url: String, onDismiss: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? FragmentActivity ?: return
    // estado mais recente (evita callback obsoleto após recomposição)
    val currentOnDismiss = androidx.compose.runtime.rememberUpdatedState(onDismiss)
    DisposableEffect(url) {
        val tag = "jw-${url.hashCode()}-${System.currentTimeMillis()}"
        val dlg = JwDownloadDialog.newInstance(url, tag)
        val fm = activity.supportFragmentManager
        fm.setFragmentResultListener(JwDownloadDialog.resultKey(tag), activity) { _, _ ->
            currentOnDismiss.value()
        }
        // se um diálogo anterior com outra tag ficou para trás, fecha antes
        (fm.findFragmentByTag(tag) as? JwDownloadDialog)?.dismissAllowingStateLoss()
        dlg.show(fm, tag)
        onDispose {
            fm.clearFragmentResultListener(JwDownloadDialog.resultKey(tag))
            runCatching { dlg.dismissAllowingStateLoss() }
            currentOnDismiss.value()
        }
    }
}
