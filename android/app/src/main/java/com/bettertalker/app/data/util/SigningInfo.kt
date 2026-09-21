package com.bettertalker.app.data.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** SHA-1 da assinatura deste build — registre no Firebase Console para o login funcionar. */
fun buildSha1(ctx: Context): String {
    return try {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return "indisponível"
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(sigs.firstOrNull()?.toByteArray() ?: return "indisponível")
        bytes.joinToString(":") { "%02X".format(it) }
    } catch (_: Exception) {
        "indisponível"
    }
}
