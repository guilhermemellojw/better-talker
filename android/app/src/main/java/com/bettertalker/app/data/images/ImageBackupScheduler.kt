package com.bettertalker.app.data.images

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bettertalker.app.data.cloud.AuthRepository
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.work.ImageBackupWorker
import java.util.concurrent.TimeUnit

/**
 * 5.4 — fila de upload automático das imagens.
 * 5.4.1 — fila separada de remoção remota (retry/GC).
 */
object ImageBackupScheduler {

    fun enqueue(ctx: Context, imageId: String) {
        enqueueWork(ctx, imageId, ImageBackupWorker.MODE_UPLOAD)
    }

    fun enqueueDelete(ctx: Context, imageId: String) {
        enqueueWork(ctx, imageId, ImageBackupWorker.MODE_DELETE)
    }

    suspend fun enqueueIfEnabled(ctx: Context, imageId: String) {
        val app = ctx.applicationContext
        if (!isEnabled(app)) return
        enqueue(app, imageId)
    }

    suspend fun enqueueAllPending(ctx: Context) {
        val app = ctx.applicationContext
        if (!isEnabled(app)) return
        val db = DbProvider.get(app)
        db.noteImageDao().pendingForBackup().forEach { enqueue(app, it.id) }
    }

    suspend fun enqueuePendingDeletes(ctx: Context) {
        val app = ctx.applicationContext
        val db = DbProvider.get(app)
        db.imageBackupDao().pendingDeletes().forEach { enqueueDelete(app, it.imageId) }
    }

    fun cancel(ctx: Context, imageId: String) {
        runCatching {
            WorkManager.getInstance(ctx.applicationContext)
                .cancelUniqueWork("image-backup-$imageId")
        }
    }

    fun cancelAll(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx.applicationContext)
                .cancelAllWorkByTag(ImageBackupWorker.TAG)
        }
    }

    private fun enqueueWork(ctx: Context, imageId: String, mode: String) {
        val app = ctx.applicationContext
        val request = OneTimeWorkRequestBuilder<ImageBackupWorker>()
            .setInputData(
                workDataOf(
                    ImageBackupWorker.KEY_IMAGE_ID to imageId,
                    ImageBackupWorker.KEY_MODE to mode,
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .addTag(ImageBackupWorker.TAG)
            .build()
        runCatching {
            WorkManager.getInstance(app).enqueueUniqueWork(
                "image-backup-$imageId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    private suspend fun isEnabled(ctx: Context): Boolean {
        val settings = SettingsStore(ctx)
        if (!settings.imageBackupEnabledValue()) return false
        val auth = AuthRepository(ctx)
        return auth.isConfigured() && auth.currentUser() != null
    }
}
