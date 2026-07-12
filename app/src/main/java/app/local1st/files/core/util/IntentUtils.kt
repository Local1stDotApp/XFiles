package app.local1st.files.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.local1st.files.core.fs.XEntry
import java.io.File

object IntentUtils {

    private fun uriFor(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(path))

    /**
     * These are launched from the application context (see [app.local1st.files.di.Graph.appContext]),
     * which is not an Activity, so every started intent — including the chooser wrapper — must
     * carry FLAG_ACTIVITY_NEW_TASK or startActivity throws.
     */
    private fun Context.launch(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Open a local file with an external app chooser. Returns false when nothing handled it. */
    fun openWith(context: Context, entry: XEntry): Boolean {
        val path = entry.localPath ?: return false
        val mime = entry.mime ?: FileTypes.mimeOf(entry.name) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, path), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return context.launch(Intent.createChooser(intent, entry.name))
    }

    fun share(context: Context, entries: List<XEntry>) {
        val uris = entries.mapNotNull { it.localPath }.map { uriFor(context, it) }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(entries.first().mime ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.launch(Intent.createChooser(intent, null))
    }

    fun installApk(context: Context, path: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, path), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.launch(intent)
    }

    fun uninstall(context: Context, packageName: String) {
        context.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    fun appInfo(context: Context, packageName: String) {
        context.launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    fun launchApp(context: Context, packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { context.launch(it) }
    }
}
