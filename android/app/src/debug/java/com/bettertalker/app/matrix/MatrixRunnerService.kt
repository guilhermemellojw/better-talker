package com.bettertalker.app.matrix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.repo.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

/**
 * Foreground service do spike C2.6: baixa/indexa o acervo e roda a matriz.
 * Debug-only; não existe no release.
 */
class MatrixRunnerService : Service() {
    companion object {
        const val TAG = "MatrixC26"
        const val CHANNEL = "matrix_c26"
        const val NOTIFICATION_ID = 2606
        const val LOG_NAME = "matriz-c26-run.log"
        @Volatile private var running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Matriz C2.6", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        startForegroundCompat(notification("Preparando matriz C2.6…"))
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BetterTalker:MatrixC26").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        scope.launch { runMatrix() }
        return START_NOT_STICKY
    }

    private suspend fun runMatrix() {
        val started = Date()
        try {
            log("START ${started}")
            val db = DbProvider.get(applicationContext)
            val rows = MatrixCsv.readRows(applicationContext)
            log("CSV rows=${rows.size} editions=${rows.map { it.edition }.distinct().size}")
            val repo = LibraryRepository(applicationContext, db)
            val acervo = AcervoBuilder(applicationContext, db, repo) { msg -> log(msg) }
                .ensure(rows) { msg ->
                    log("PROGRESS $msg")
                    updateNotification(msg)
                }
            MatrixCsv.writeAcervo(applicationContext, acervo)
            log("ACERVO DONE ready=${acervo.ready} failed=${acervo.failed} unavailable=${acervo.unavailable} bytes=${acervo.totalBytes}")
            val matrix = MatrixRunner(applicationContext, db) { msg -> log(msg) }
                .run(rows, acervo)
            MatrixCsv.writeResults(applicationContext, matrix.results)
            MatrixCsv.writeSummary(applicationContext, acervo, matrix)
            log(
                "DONE refs=${matrix.total} resolved=${matrix.resolved} partial=${matrix.partial} " +
                    "text_not_found=${matrix.textNotFound} edition_missing=${matrix.editionMissing} " +
                    "other=${matrix.other} usable=${matrix.usable}"
            )
            updateNotification(
                "Concluído: ${matrix.resolved} resolvidas, ${matrix.partial} parciais, " +
                    "${matrix.editionMissing} sem edição"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("FAILED ${e::class.simpleName}: ${e.message}")
            updateNotification("Falhou: ${e.message?.take(80)}")
        } finally {
            runCatching { wakeLock?.release() }
            wakeLock = null
            running = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Matriz C2.6")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun log(msg: String) {
        android.util.Log.i(TAG, msg)
        runCatching {
            File(filesDir, LOG_NAME).appendText("${Date()} $msg\n")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
