package com.bettertalker.app.data.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bettertalker.app.BuildConfig
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Puro/testável: só agenda sync com build beta, opt-in, Firebase e login. */
internal fun syncGate(
    buildSupportsSync: Boolean,
    optedIn: Boolean,
    configured: Boolean,
    signedIn: Boolean,
): Boolean = buildSupportsSync && optedIn && configured && signedIn

object SyncScheduler {
    private const val NOW = "sync-now"
    private const val PERIODIC = "sync-periodic"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val debounceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null

    /** Sync imediata (debounce via REPLACE) após cada alteração local. */
    fun requestSync(ctx: Context) {
        if (!BuildConfig.CLOUD_SYNC) return
        val app = ctx.applicationContext
        scope.launch {
            if (!enabled(app)) return@launch
            enqueueNow(app, null)
        }
    }

    /** M2: primeiro sync com a escolha do usuário (upload/download/merge). */
    fun requestFirstSync(ctx: Context, mode: String) {
        if (!BuildConfig.CLOUD_SYNC) return
        val app = ctx.applicationContext
        scope.launch {
            if (!enabled(app, allowPendingFirstSync = true)) return@launch
            enqueueNow(app, mode)
        }
    }

    /**
     * Sync existe nos dois flavors (CLOUD_SYNC), com opt-in explícito, Firebase
     * configurado e sessão ativa. O worker repete os três primeiros checks
     * para neutralizar work antigo/reenfileirado.
     */
    private suspend fun enabled(
        ctx: Context,
        allowPendingFirstSync: Boolean = false,
    ): Boolean {
        if (!BuildConfig.CLOUD_SYNC) return false
        val settings = SettingsStore(ctx)
        val optedIn = runCatching { settings.syncBetaEnabledValue() }.getOrDefault(false)
        if (!optedIn) return false
        // Antes da escolha Enviar/Baixar/Mesclar, nenhum worker automático
        // pode rodar (o worker só aceita o primeiro sync com modo explícito).
        if (!allowPendingFirstSync && settings.syncFirstPendingValue()) return false
        val auth = AuthRepository(ctx)
        return syncGate(
            buildSupportsSync = true,
            optedIn = true,
            configured = auth.isConfigured(),
            signedIn = auth.currentUser() != null,
        )
    }

    private fun enqueueNow(ctx: Context, firstMode: String?) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .apply {
                    if (firstMode != null) {
                        setInputData(
                            androidx.work.workDataOf(
                                SyncWorker.KEY_FIRST_SYNC_MODE to firstMode
                            )
                        )
                    }
                }
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    /**
     * Auto-sync pós-edição (Gap A): coalesce rajadas de save num único worker
     * (padrão 2s) — o job anterior é cancelado a cada nova edição. O estado é
     * de PROCESSO (compartilhado por todos os repositórios).
     */
    fun requestSyncDebounced(ctx: Context, delayMs: Long = 2_000) {
        if (!BuildConfig.CLOUD_SYNC) return
        debounceJob?.cancel()
        debounceJob = debounceScope.launch {
            delay(delayMs)
            requestSync(ctx)
        }
    }

    /** Sync periódica de fundo (6h, só com rede). */
    fun ensurePeriodic(ctx: Context) {
        if (!BuildConfig.CLOUD_SYNC) return
        val app = ctx.applicationContext
        scope.launch {
            if (!enabled(app)) return@launch
            runCatching {
                WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                    PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                )
            }
        }
    }

    /** Opt-out/limpeza: nenhum worker antigo pode ressuscitar o sync. */
    fun cancelAll(ctx: Context) {
        runCatching {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            wm.cancelUniqueWork(NOW)
            wm.cancelUniqueWork(PERIODIC)
        }
    }
}
