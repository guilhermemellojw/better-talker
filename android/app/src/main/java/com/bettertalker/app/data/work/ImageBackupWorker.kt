package com.bettertalker.app.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettertalker.app.data.cloud.AuthRepository
import com.bettertalker.app.data.cloud.SyncScheduler
import com.bettertalker.app.data.cloud.SyncStatus
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.images.ImageBackupOutcome
import com.bettertalker.app.data.images.ImageBackupRepository
import com.bettertalker.app.data.prefs.SettingsStore

/**
 * 5.4 — sobe uma imagem para o Storage em segundo plano.
 * 5.4.1 — também retenta a remoção de um objeto (`mode=delete`).
 *
 * Gates repetidos aqui (opt-in, Firebase e login) para neutralizar work
 * antigo/reenfileirado. Sem login o worker termina em sucesso e a imagem
 * fica pendente; o próximo login enfileira o backfill.
 */
class ImageBackupWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val mode = inputData.getString(KEY_MODE) ?: MODE_UPLOAD
        val db = DbProvider.get(app)
        val repo = ImageBackupRepository.create(app, db)
        val auth = AuthRepository(app)

        if (mode == MODE_RESTORE) {
            if (!auth.isConfigured() || auth.currentUser() == null) {
                return Result.success()
            }
            val uid = auth.currentUser()?.uid ?: return Result.success()
            SyncStatus.set(SyncStatus.State.SyncingImages)
            val report = repo.restore(uid)
            SyncScheduler.requestSync(app)
            if (report.failed > 0) {
                SyncStatus.set(
                    SyncStatus.State.Error(
                        "Não foi possível baixar ${report.failed} imagem(ns).",
                        System.currentTimeMillis(),
                    )
                )
                return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            }
            SyncStatus.set(SyncStatus.State.Success(System.currentTimeMillis()))
            return Result.success()
        }

        val imageId = inputData.getString(KEY_IMAGE_ID) ?: return Result.success()

        if (mode == MODE_DELETE) {
            if (!auth.isConfigured() || auth.currentUser() == null) {
                return Result.success()
            }
            val ok = repo.deleteCloud(imageId)
            if (ok) SyncScheduler.requestSync(app)
            return if (ok) {
                Result.success()
            } else if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        val settings = SettingsStore(app)
        if (!settings.syncBetaEnabledValue()) return Result.success()

        if (!auth.isConfigured() || auth.currentUser() == null) return Result.success()

        return when (val outcome = repo.upload(imageId)) {
            is ImageBackupOutcome.Uploaded -> {
                SyncScheduler.requestSync(app)
                Result.success()
            }
            ImageBackupOutcome.Missing,
            ImageBackupOutcome.Deferred -> Result.success()

            is ImageBackupOutcome.Failed ->
                if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
        }
    }

    companion object {
        const val KEY_IMAGE_ID = "imageId"
        const val KEY_MODE = "mode"
        const val MODE_UPLOAD = "upload"
        const val MODE_DELETE = "delete"
        const val MODE_RESTORE = "restore"
        const val TAG = "image-backup"
        private const val MAX_ATTEMPTS = 3
    }
}
