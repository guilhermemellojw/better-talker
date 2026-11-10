package com.bettertalker.app.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettertalker.app.data.cloud.AuthRepository
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.images.ImageBackupOutcome
import com.bettertalker.app.data.images.ImageBackupRepository
import com.bettertalker.app.data.prefs.SettingsStore

/**
 * 5.4 — sobe uma imagem para o Storage em segundo plano.
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
        val imageId = inputData.getString(KEY_IMAGE_ID) ?: return Result.success()
        val app = applicationContext
        val settings = SettingsStore(app)
        if (!settings.imageBackupEnabledValue()) return Result.success()

        val auth = AuthRepository(app)
        if (!auth.isConfigured() || auth.currentUser() == null) return Result.success()

        val db = DbProvider.get(app)
        val repo = ImageBackupRepository.create(app, db)
        return when (val outcome = repo.upload(imageId)) {
            is ImageBackupOutcome.Uploaded,
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
        const val TAG = "image-backup"
        private const val MAX_ATTEMPTS = 3
    }
}
