package app.local1st.files.core.util

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.InputStream

/**
 * Installs an APK — or a whole split app — through a [PackageInstaller] session.
 *
 * A lone `base.apk` of a split app can't be installed by the usual `ACTION_VIEW` intent; the
 * base and every split must be written into one session and committed together. That same
 * session path installs an ordinary single APK too, so it is the one install route we use.
 *
 * The system shows its own confirmation UI (and, if the user hasn't allowed this app to install
 * unknown apps, routes them to grant it first). The final result surfaces as a toast.
 */
object ApkInstaller {

    private const val ACTION_RESULT = "app.local1st.files.INSTALL_RESULT"
    private const val CONFIRM_CHANNEL_ID = "install_confirmation"

    /** One APK to feed into a session: its entry [name], byte [size] (or ≤0 if unknown), bytes. */
    class ApkSource(val name: String, val size: Long, val open: () -> InputStream)

    /**
     * Writes every APK in [apks] into a fresh session and commits it as a single package named
     * [label] (for user-facing messages only). Blocking IO — call off the main thread.
     */
    @Throws(Exception::class)
    fun install(
        context: Context,
        label: String,
        apks: List<ApkSource>,
        onResult: ((success: Boolean) -> Unit)? = null,
    ) {
        require(apks.isNotEmpty()) { "No APK to install" }
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        var receiver: BroadcastReceiver? = null
        try {
            installer.openSession(sessionId).use { session ->
                apks.forEachIndexed { i, apk ->
                    session.openWrite("apk_$i", 0, if (apk.size > 0) apk.size else -1).use { out ->
                        apk.open().use { input -> input.copyTo(out) }
                        session.fsync(out)
                    }
                }
                // Registered before commit so the STATUS_PENDING_USER_ACTION prompt isn't missed.
                receiver = registerResultReceiver(app, sessionId, label, onResult)
                val callback = PendingIntent.getBroadcast(
                    app,
                    sessionId,
                    Intent(actionFor(sessionId)).setPackage(app.packageName),
                    pendingIntentFlags(),
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun actionFor(sessionId: Int) = "$ACTION_RESULT.$sessionId"

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        // The system fills the result extras in, so the PendingIntent must be mutable on S+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return flags
    }

    /** A one-shot receiver, scoped to [sessionId], that drives the prompt and reports the result. */
    private fun registerResultReceiver(
        app: Context,
        sessionId: Int,
        label: String,
        onResult: ((success: Boolean) -> Unit)?,
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        confirmIntent(intent)?.let { confirm ->
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            val canNotify = NotificationManagerCompat.from(app).areNotificationsEnabled()
                            if (isAppForeground()) {
                                if (runCatching { app.startActivity(confirm) }.isFailure && canNotify) {
                                    postConfirmationNotification(app, sessionId, label, confirm)
                                }
                            } else if (canNotify) {
                                postConfirmationNotification(app, sessionId, label, confirm)
                            } else {
                                runCatching { app.startActivity(confirm) }
                            }
                        }
                        return // Not terminal: keep listening for the real outcome.
                    }
                    PackageInstaller.STATUS_SUCCESS -> toast(app, "Installed $label")
                    else -> {
                        val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        toast(app, "Install failed: ${why ?: "unknown error"}")
                    }
                }
                notificationManager(app).cancel(sessionId)
                runCatching { app.unregisterReceiver(this) }
                runCatching { onResult?.invoke(status == PackageInstaller.STATUS_SUCCESS) }
            }
        }
        val filter = IntentFilter(actionFor(sessionId))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        return receiver
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun isAppForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun postConfirmationNotification(
        app: Context,
        sessionId: Int,
        label: String,
        confirmIntent: Intent,
    ) {
        val manager = notificationManager(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CONFIRM_CHANNEL_ID,
                    "Install confirmations",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Prompts waiting for package-install confirmation" },
            )
        }
        val tap = PendingIntent.getActivity(
            app,
            sessionId,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            sessionId,
            NotificationCompat.Builder(app, CONFIRM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Confirm installation")
                .setContentText("Tap to continue installing $label")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
