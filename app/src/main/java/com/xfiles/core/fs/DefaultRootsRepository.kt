package com.xfiles.core.fs

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.xfiles.core.util.Format
import java.io.File

/**
 * Pane roots from [StorageManager]: mounted storage volumes plus the
 * app-manager root and (when readable) the filesystem root `/`.
 */
class DefaultRootsRepository(private val context: Context) : RootsRepository {

    override fun volumes(): List<Volume> {
        val storageManager =
            context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return storageManager.storageVolumes.mapNotNull { volume ->
            if (volume.state != Environment.MEDIA_MOUNTED &&
                volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
            ) {
                return@mapNotNull null
            }
            val dir = directoryOf(volume) ?: return@mapNotNull null
            toVolume(volume, dir)
        }
    }

    override fun paneRoots(): List<XEntry> {
        val roots = ArrayList<XEntry>()
        volumes().mapTo(roots) { it.entry }
        roots += XEntry(
            id = "${XId.SCHEME_APPS}://",
            name = "App manager",
            isDir = true,
            kind = EntryKind.APPS_ROOT,
            canWrite = false,
        )
        // Superuser access to "/" (X-plore's "Root"). Prefer real root via `su`; fall back
        // to a plain non-privileged view only if "/" happens to be readable without it.
        if (RootShell.isAvailable()) {
            roots += RootFileSystem.rootEntry()
        } else {
            val fsRoot = File("/")
            if (fsRoot.canRead()) {
                roots += XEntry(
                    id = XId.file("/"),
                    name = "Root (read-only)",
                    isDir = true,
                    kind = EntryKind.DIR,
                    canWrite = fsRoot.canWrite(),
                    localPath = "/",
                )
            }
        }
        return roots
    }

    private fun directoryOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        // API 26-29: no public directory getter; StorageVolume#getPath is a
        // stable hidden method on these releases.
        val reflectedPath = try {
            StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
        } catch (e: ReflectiveOperationException) {
            null
        }
        if (reflectedPath != null) return File(reflectedPath)
        return if (volume.isPrimary) Environment.getExternalStorageDirectory() else null
    }

    private fun toVolume(volume: StorageVolume, dir: File): Volume? {
        val path = dir.absolutePath
        val total: Long
        val free: Long
        try {
            val stat = StatFs(path)
            total = stat.totalBytes
            free = stat.availableBytes
        } catch (e: IllegalArgumentException) {
            // Directory reported by the platform but not stat-able: not usable.
            return null
        }
        val label = volume.getDescription(context)?.takeIf { it.isNotBlank() }
            ?: if (volume.isPrimary) "Internal storage" else "Storage"
        val used = (total - free).coerceAtLeast(0L)
        val entry = XEntry(
            id = XId.file(path),
            name = label,
            isDir = true,
            kind = kindOf(volume, label),
            badge = "${Format.bytes(free)} free of ${Format.bytes(total)}",
            progress = if (total > 0) used.toFloat() / total.toFloat() else -1f,
            localPath = path,
            canWrite = true,
        )
        return Volume(entry, label, total, free)
    }

    private fun kindOf(volume: StorageVolume, label: String): EntryKind = when {
        volume.isPrimary || volume.isEmulated -> EntryKind.VOLUME_INTERNAL
        label.contains("usb", ignoreCase = true) -> EntryKind.VOLUME_USB
        volume.isRemovable -> EntryKind.VOLUME_SD
        else -> EntryKind.VOLUME_INTERNAL
    }
}
