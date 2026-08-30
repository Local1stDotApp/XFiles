package app.local1st.files.core.fs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.annotation.RequiresApi
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.prefs.Favorite
import app.local1st.files.core.prefs.SafLocation
import app.local1st.files.core.util.Format
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Pane roots from [StorageManager]: mounted storage volumes, granted document trees,
 * pinned favorites, plus the app-manager root and (when the Settings switch is on)
 * the filesystem root `/`.
 *
 * Favorites and stat are injected as lambdas so this class stays free of the
 * DI graph (wired in GraphInit).
 */
class DefaultRootsRepository(
    private val context: Context,
    private val favorites: () -> List<Favorite> = { emptyList() },
    private val safLocations: () -> List<SafLocation> = { emptyList() },
    private val statById: (String) -> XEntry? = { null },
) : RootsRepository {

    private val storageManager =
        context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    private val _volumeEpoch = MutableStateFlow(0L)
    override val volumeEpoch: StateFlow<Long> = _volumeEpoch

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            bumpVolumeEpoch()
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            registerStorageVolumeCallback()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(mediaReceiver, filter)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun registerStorageVolumeCallback() {
        storageManager.registerStorageVolumeCallback(
            Executor { it.run() },
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    bumpVolumeEpoch()
                }
            },
        )
    }

    private fun bumpVolumeEpoch() {
        _volumeEpoch.update { it + 1 }
    }

    override fun volumes(): List<Volume> {
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
        // Visibility follows the Settings switch, not a successful `su` probe. Opening `/`
        // still needs superuser; without it the row stays and listing explains why.
        // Shizuku must not be dressed up as a filesystem root — it cannot browse `/`.
        if (PrivilegedAccess.enabled) {
            specials += RootFileSystem.rootEntry()
        }
        // Favorites are collision-checked against EVERY other root (volumes and specials
        // alike, whichever side of them it renders on) — a duplicate id at the top level
        // would break the tree's position keys (see TreeNode.key).
        val taken = HashSet<String>()
        volumeEntries.mapTo(taken) { it.id }
        specials.mapTo(taken) { it.id }
        val roots = ArrayList<XEntry>(volumeEntries.size + specials.size + 4)
        roots += volumeEntries
        addSafLocations(roots, taken)
        addFavorites(roots, taken)
        roots += specials
        return roots
    }

    /**
     * Appends granted document trees as pane roots. A location whose provider is currently
     * missing still shows, marked unavailable, so the grant isn't silently lost.
     */
    private fun addSafLocations(roots: MutableList<XEntry>, taken: MutableSet<String>) {
        for (location in safLocations()) {
            val id = runCatching { XId.saf(location.id) }.getOrNull() ?: continue
            if (!taken.add(id)) continue
            val stat = runCatching { statById(id) }.getOrNull()
            roots += safLocationRoot(location, stat)
        }
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
            volume.directory?.let { return it }
        }
        // API 26-29: no public directory getter; StorageVolume#getPath is a
        // stable hidden method on these releases. API 30+ still has it as a
        // fallback when `directory` is null for a just-mounted USB volume.
        val reflectedPath = try {
            StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
        } catch (e: ReflectiveOperationException) {
            null
        }
        if (!reflectedPath.isNullOrBlank()) return File(reflectedPath)
        val uuid = volume.uuid
        if (!uuid.isNullOrBlank()) {
            val guessed = File("/storage/$uuid")
            if (guessed.isDirectory) return guessed
        }
        // Do not call getExternalFilesDirs: it mkdirs Android/data/<pkg>/files on a
        // just-inserted USB stick. A later volumeEpoch retry picks the path up.
        return if (volume.isPrimary) Environment.getExternalStorageDirectory() else null
    }

    private fun toVolume(volume: StorageVolume, dir: File): Volume {
        val path = dir.absolutePath
        var total = -1L
        var free = -1L
        try {
            val stat = StatFs(path)
            total = stat.totalBytes
            free = stat.availableBytes
        } catch (_: IllegalArgumentException) {
            // Just-mounted USB FUSE is sometimes not stat-able yet. Keep the root visible.
        }
        val label = volume.getDescription(context)?.takeIf { it.isNotBlank() }
            ?: if (volume.isPrimary) "Internal storage" else "Storage"
        val used = if (total > 0 && free >= 0) (total - free).coerceAtLeast(0L) else -1L
        val entry = XEntry(
            id = XId.file(path),
            name = label,
            isDir = true,
            kind = volumeKind(
                isPrimary = volume.isPrimary,
                isEmulated = volume.isEmulated,
                isRemovable = volume.isRemovable,
                description = label,
                path = path,
                diskIsUsb = diskIsUsb(volume),
            ),
            badge = if (total > 0 && free >= 0) {
                "${Format.bytes(free)} free of ${Format.bytes(total)}"
            } else {
                null
            },
            progress = if (total > 0 && used >= 0) used.toFloat() / total.toFloat() else -1f,
            localPath = path,
            canWrite = volume.state != Environment.MEDIA_MOUNTED_READ_ONLY,
        )
        return Volume(entry, label, total.coerceAtLeast(0L), free.coerceAtLeast(0L))
    }

    /**
     * Best-effort USB bit from hidden DiskInfo. Returns null when the platform
     * hides it; callers must not treat that as "not USB".
     */
    private fun diskIsUsb(volume: StorageVolume): Boolean? {
        runCatching {
            val method = volume.javaClass.methods.firstOrNull {
                it.name == "isUsb" && it.parameterCount == 0
            }
            if (method != null) return method.invoke(volume) as? Boolean
        }
        return runCatching {
            val infos = StorageManager::class.java.getMethod("getVolumes")
                .invoke(storageManager) as? List<*> ?: return@runCatching null
            val infoClass = Class.forName("android.os.storage.VolumeInfo")
            val getFsUuid = infoClass.getMethod("getFsUuid")
            val getDisk = infoClass.getMethod("getDisk")
            val isUsb = Class.forName("android.os.storage.DiskInfo").getMethod("isUsb")
            val uuid = volume.uuid
            for (info in infos) {
                if (info == null) continue
                val fsUuid = getFsUuid.invoke(info) as? String
                if (uuid.isNullOrBlank() || fsUuid == null) continue
                if (!fsUuid.equals(uuid, ignoreCase = true)) continue
                val disk = getDisk.invoke(info) ?: return@runCatching null
                return@runCatching isUsb.invoke(disk) as? Boolean
            }
            null
        }.getOrNull()
    }
}
