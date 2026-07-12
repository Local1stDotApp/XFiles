package com.xfiles.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xfiles.core.fs.XEntry
import java.io.File

object IntentUtils {

    private fun uriFor(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(path))

    /** Open a local file with an external app chooser. Returns false when nothing handled it. */
    fun openWith(context: Context, entry: XEntry): Boolean {
        val path = entry.localPath ?: return false
        val mime = entry.mime ?: FileTypes.mimeOf(entry.name) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, path), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(intent, entry.name))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
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
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }

    fun installApk(context: Context, path: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, path), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }

    fun uninstall(context: Context, packageName: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }
    }

    fun appInfo(context: Context, packageName: String) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    fun launchApp(context: Context, packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            runCatching { context.startActivity(it) }
        }
    }
}
