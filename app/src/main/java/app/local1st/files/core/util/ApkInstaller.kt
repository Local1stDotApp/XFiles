package app.local1st.files.core.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
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

    /** One APK to feed into a session: its entry [name], byte [size] (or ≤0 if unknown), bytes. */
    class ApkSource(val name: String, val size: Long, val open: () -> InputStream)

    /**
     * Writes every APK in [apks] into a fresh session and commits it as a single package named
     * [label] (for user-facing messages only). Blocking IO — call off the main thread.
     */
    @Throws(Exception::class)
    fun install(context: Context, label: String, apks: List<ApkSource>) {
        require(apks.isNotEmpty()) { "No APK to install" }
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apks.forEachIndexed { i, apk ->
                session.openWrite("apk_$i", 0, if (apk.size > 0) apk.size else -1).use { out ->
                    apk.open().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
            }
            // Registered before commit so the STATUS_PENDING_USER_ACTION prompt isn't missed.
            registerResultReceiver(app, sessionId, label)
            val callback = PendingIntent.getBroadcast(
                app,
                sessionId,
                Intent(actionFor(sessionId)).setPackage(app.packageName),
                pendingIntentFlags(),
            )
            session.commit(callback.intentSender)
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
    private fun registerResultReceiver(app: Context, sessionId: Int, label: String) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        confirmIntent(intent)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return // Not terminal: keep listening for the real outcome.
                    }
                    PackageInstaller.STATUS_SUCCESS -> toast(app, "Installed $label")
                    else -> {
                        val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        toast(app, "Install failed: ${why ?: "unknown error"}")
                    }
                }
                runCatching { app.unregisterReceiver(this) }
            }
        }
        val filter = IntentFilter(actionFor(sessionId))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
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
}
