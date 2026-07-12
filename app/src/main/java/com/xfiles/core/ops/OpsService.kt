package com.xfiles.core.ops

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xfiles.core.util.Format
import com.xfiles.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps file operations (copy/move/zip/extract) running when the app is backgrounded.
 * The engine itself lives on the app-lifetime scope; this service holds the process alive
 * with a foreground notification + wake lock, and mirrors the running op's progress.
 * It stops itself as soon as no operations remain.
 */
class OpsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xfiles:ops")
            .apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            Graph.opEngine.active.value.forEach { it.cancel() }
        }

        // Must call startForeground promptly. Guard the rare race where the app is no longer
        // foreground-eligible (the op keeps running on the app scope regardless).
        runCatching {
            startForeground(NOTIF_ID, buildNotification(Graph.opEngine.active.value.size, null))
        }

        if (!collecting) {
            collecting = true
            scope.launch {
                Graph.opEngine.active
                    .flatMapLatest { ops ->
                        val first = ops.firstOrNull()
                        if (first == null) flowOf(0 to null)
                        else first.progress.map { ops.size to it }
                    }
                    .collect { (count, progress) ->
                        if (count == 0) {
                            stop()
                        } else {
                            // Renew the wake lock each tick so ops longer than the timeout keep going.
                            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                            notificationManager().notify(NOTIF_ID, buildNotification(count, progress))
                        }
                    }
            }
        }
        // No in-memory op state survives a process restart, so don't re-deliver.
        return START_NOT_STICKY
    }

    /** Android 14+ dataSync FGS time limit: cancel outstanding ops and stop cleanly. */
    override fun onTimeout(startId: Int) {
        Graph.opEngine.active.value.forEach { it.cancel() }
        stop()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(count: Int, progress: OpProgress?): android.app.Notification {
        val title = when {
            count > 1 -> "$count file operations"
            progress != null -> progress.title
            else -> "Working…"
        }
        val text = progress?.let {
            val pct = (it.fraction * 100).toInt()
            when (it.state) {
                OpState.SCANNING -> "Scanning… ${it.currentItem}"
                else -> "$pct%  ·  ${Format.bytes(it.doneBytes)} / ${Format.bytes(it.totalBytes)}"
            }
        } ?: ""

        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OpsService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)

        if (progress != null && progress.state != OpState.SCANNING && progress.totalBytes > 0) {
            builder.setProgress(100, (progress.fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File operations",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progress of copy, move, compress and extract" }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "file_operations"
        private const val NOTIF_ID = 42
        private const val ACTION_CANCEL_ALL = "com.xfiles.ops.CANCEL_ALL"
        private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L // 1 hour safety cap

        /** Starts (or refreshes) the foreground service to cover currently-running ops. */
        fun start(context: Context) {
            val intent = Intent(context, OpsService::class.java)
            // Ops are submitted from the foreground, so this is normally allowed; guard the
            // rare background-start race (ForegroundServiceStartNotAllowedException) — the op
            // still runs on the app-lifetime scope even without the service.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
