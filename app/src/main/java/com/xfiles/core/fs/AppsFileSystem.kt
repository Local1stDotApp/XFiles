package com.xfiles.core.fs

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Read-only pseudo-filesystem exposing installed apps (X-plore's "App manager").
 *
 * - `apps://` is the root container listing one [EntryKind.APP] entry per installed package.
 * - `apps://<packageName>` identifies a single app; [openIn] streams its base APK, which is
 *   what lets the operation engine copy an app out as an .apk file.
 * - System apps are marked [XEntry.hidden] so the default hidden-entries filter shows
 *   user-installed apps only; enabling "show hidden" reveals system apps too.
 */
class AppsFileSystem(private val context: Context) : XFileSystem {

    override val scheme: String = XId.SCHEME_APPS

    override fun list(dir: XEntry): List<XEntry> {
        if (dir.scheme != scheme || dir.path.isNotEmpty()) {
            throw IOException("'${dir.name}' is not a listable app container")
        }
        val pm = context.packageManager
        return installedApplications(pm)
            .mapNotNull { app -> toEntry(pm, app) }
            .sortedWith(
                // User apps (hidden=false) before system apps, each group sorted by label.
                compareBy<XEntry> { it.hidden }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.path },
            )
    }

    override fun stat(id: String): XEntry? {
        if (XId.schemeOf(id) != scheme) return null
        val packageName = id.substringAfter("://")
        if (packageName.isEmpty()) return rootEntry()
        val pm = context.packageManager
        val app = try {
            applicationInfo(pm, packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return toEntry(pm, app)
    }

    override fun openIn(entry: XEntry): InputStream {
        if (entry.kind != EntryKind.APP) {
            throw IOException("'${entry.name}' is not an app and cannot be opened")
        }
        val apkPath = entry.localPath
            ?: stat(entry.id)?.localPath
            ?: throw IOException("App '${entry.name}' is not installed")
        val apk = File(apkPath)
        if (!apk.exists()) {
            throw IOException("APK of '${entry.name}' not found at $apkPath")
        }
        return FileInputStream(apk)
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream =
        throw IOException("Not supported")

    override fun mkdir(parentDir: XEntry, name: String): XEntry =
        throw IOException("Not supported")

    override fun delete(entry: XEntry) {
        throw IOException("Not supported")
    }

    override fun rename(entry: XEntry, newName: String): XEntry =
        throw IOException("Not supported")

    override fun canWrite(entry: XEntry): Boolean = false

    private fun toEntry(pm: PackageManager, app: ApplicationInfo): XEntry? {
        val sourceDir = app.sourceDir ?: return null
        val pkg = try {
            packageInfo(pm, app.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null // uninstalled between enumeration and resolution
        }
        val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val versionName = pkg.versionName ?: "?"
        return XEntry(
            id = "$scheme://${app.packageName}",
            name = app.loadLabel(pm).toString(),
            isDir = false,
            size = File(sourceDir).length(),
            mtime = pkg.lastUpdateTime,
            mime = APK_MIME,
            hidden = isSystem,
            canRead = true,
            canWrite = false,
            kind = EntryKind.APP,
            badge = "$versionName · ${app.packageName}",
            localPath = sourceDir,
        )
    }

    private fun installedApplications(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

    @Throws(PackageManager.NameNotFoundException::class)
    private fun applicationInfo(pm: PackageManager, packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }

    @Throws(PackageManager.NameNotFoundException::class)
    private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }

    companion object {
        const val ROOT_ID = "${XId.SCHEME_APPS}://"

        private const val APK_MIME = "application/vnd.android.package-archive"

        /** Root entry for pane roots ("App manager" special root). */
        fun rootEntry(): XEntry = XEntry(
            id = ROOT_ID,
            name = "App manager",
            isDir = true,
            canWrite = false,
            kind = EntryKind.APPS_ROOT,
        )
    }
}
