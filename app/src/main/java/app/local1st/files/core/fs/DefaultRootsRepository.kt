package app.local1st.files.core.fs

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import app.local1st.files.core.prefs.Favorite
import app.local1st.files.core.util.Format
import java.io.File

/**
 * Pane roots from [StorageManager]: mounted storage volumes, pinned favorites,
 * plus the app-manager root and (when readable) the filesystem root `/`.
 *
 * Favorites and stat are injected as lambdas so this class stays free of the
 * DI graph (wired in GraphInit).
 */
class DefaultRootsRepository(
    private val context: Context,
    private val favorites: () -> List<Favorite> = { emptyList() },
    private val statById: (String) -> XEntry? = { null },
) : RootsRepository {

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
        val volumeEntries = volumes().map { it.entry }
        val specials = ArrayList<XEntry>()
        specials += XEntry(
            id = "${XId.SCHEME_APPS}://",
            name = "App manager",
            isDir = true,
            kind = EntryKind.APPS_ROOT,
            canWrite = false,
        )
        // Superuser access to "/" (X-plore's "Root"), gated behind the Settings switch so it
        // (and the `su` probe) only appears when the user opts in. Prefer real root via `su`;
        // fall back to a plain non-privileged view only if "/" is readable without it.
        if (RootAccess.enabled) {
            if (RootShell.isAvailable()) {
                specials += RootFileSystem.rootEntry()
            } else {
                val fsRoot = File("/")
                if (fsRoot.canRead()) {
                    specials += XEntry(
                        id = XId.file("/"),
                        name = "Root (read-only)",
                        isDir = true,
                        kind = EntryKind.DIR,
                        canWrite = fsRoot.canWrite(),
                        localPath = "/",
                    )
                }
            }
        }
        // Favorites are collision-checked against EVERY other root (volumes and specials
        // alike, whichever side of them it renders on) — a duplicate id at the top level
        // would break the tree's position keys (see TreeNode.key).
        val taken = HashSet<String>()
        volumeEntries.mapTo(taken) { it.id }
        specials.mapTo(taken) { it.id }
        val roots = ArrayList<XEntry>(volumeEntries.size + specials.size + 4)
        roots += volumeEntries
        addFavorites(roots, taken)
        roots += specials
        return roots
    }

    /**
     * Appends pinned favorites as top-level shortcut roots. A favorite keeps its real
     * entry id, so expanding it browses the actual location. A favorite whose target
     * is currently missing (deleted, volume unmounted) still shows, marked unavailable,
     * so the shortcut isn't silently lost.
     */
    private fun addFavorites(roots: MutableList<XEntry>, taken: MutableSet<String>) {
        for (fav in favorites()) {
            if (!taken.add(fav.id)) continue
            val stat = runCatching { statById(fav.id) }.getOrNull()
            val fallbackName = fav.id.substringAfter("://").trimEnd('/')
                .substringAfterLast('/').substringAfterLast(XId.ARCHIVE_SEP).ifEmpty { "/" }
            val entry = (stat ?: XEntry(id = fav.id, name = fallbackName, isDir = fav.isDir, canWrite = false))
                .copy(
                    pinned = true,
                    badge = if (stat == null) "Not available" else fav.id.substringAfter("://"),
                )
            // A stat of "/" yields an empty name; a nameless pinned row is unusable.
            roots += if (entry.name.isEmpty()) entry.copy(name = fallbackName) else entry
        }
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
