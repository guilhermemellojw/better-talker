package com.bettertalker.app.data.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bettertalker.app.data.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val NOW = "sync-now"
    private const val PERIODIC = "sync-periodic"

    private val debounceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null

    /** Sync imediata (debounce via REPLACE) após cada alteração local. */
    fun requestSync(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }

    /**
     * Auto-sync pós-edição (Gap A): coalesce rajadas de save num único worker
     * (padrão 2s) — o job anterior é cancelado a cada nova edição. O estado é
     * de PROCESSO (compartilhado por todos os repositórios).
     */
    fun requestSyncDebounced(ctx: Context, delayMs: Long = 2_000) {
        debounceJob?.cancel()
        debounceJob = debounceScope.launch {
            delay(delayMs)
            requestSync(ctx)
        }
    }

    /** Sync periódica de fundo (6h, só com rede). */
    fun ensurePeriodic(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
