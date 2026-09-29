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
import app.local1st.files.R
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.prefs.Favorite
import app.local1st.files.core.prefs.SafLocation
import app.local1st.files.core.util.Format
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val mountedLock = Any()
    @Volatile
    private var mountSnapshot = MountPublish()
    @Volatile
    private var pendingRetryAt = 0L
    private val scanGeneration = AtomicLong(0)
    private val refreshQueued = AtomicBoolean(false)
    private val _mountedVolumes = MutableStateFlow<List<MountedVolume>>(emptyList())
    override val mountedVolumes: StateFlow<List<MountedVolume>> = _mountedVolumes
    private val mountedScanner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xfiles-volumes").apply { isDaemon = true }
    }

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
        refreshMountedAsync()
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
        refreshMountedAsync()
    }

    override fun peekMountedVolumes(): List<MountedVolume> {
        if (mountSnapshot.needsRefresh(_volumeEpoch.value) &&
            SystemClock.uptimeMillis() >= pendingRetryAt
        ) {
            refreshMountedAsync()
        }
        return mountSnapshot.cached
    }

    override fun currentMountedVolumes(): List<MountedVolume> {
        val epoch = _volumeEpoch.value
        val snapshot = mountSnapshot
        if (!snapshot.needsRefresh(epoch)) return snapshot.cached
        if (snapshot.pending && SystemClock.uptimeMillis() < pendingRetryAt) return snapshot.cached
        synchronized(mountedLock) {
            val now = _volumeEpoch.value
            val current = mountSnapshot
            if (!current.needsRefresh(now)) return current.cached
            if (current.pending && SystemClock.uptimeMillis() < pendingRetryAt) return current.cached
            // Generation is the start order. Taking it after the scan lets an older
            // scan seal over a newer pending observation.
            val generation = scanGeneration.incrementAndGet()
            val scan = scanMounted(withStats = false)
            if (_volumeEpoch.value == now) {
                publishMounted(now, generation, scan.mounted, scan.pending)
            }
            return mountSnapshot.cached
        }
    }

    override fun requireMountedVolumes(): List<MountedVolume> {
        currentMountedVolumes()
        val snapshot = mountSnapshot
        // A scan abandoned because the epoch moved must not be treated as the new volume list.
        if (snapshot.pending || snapshot.sealedEpoch != _volumeEpoch.value) {
            throw IOException("Storage is still mounting")
        }
        return snapshot.cached
    }

    override fun hasUnresolvedVolume(): Boolean {
        currentMountedVolumes()
        val snapshot = mountSnapshot
        return snapshot.pending || snapshot.sealedEpoch != _volumeEpoch.value
    }

    private fun refreshMountedAsync() {
        if (!refreshQueued.compareAndSet(false, true)) return
        val epoch = _volumeEpoch.value
        val generation = scanGeneration.incrementAndGet()
        mountedScanner.execute {
            try {
                val current = mountSnapshot
                if (!current.needsRefresh(epoch)) return@execute
                val scan = scanMounted(withStats = false)
                if (_volumeEpoch.value != epoch) return@execute
                publishMounted(epoch, generation, scan.mounted, scan.pending)
                if (!scan.pending) return@execute
                // directoryOf can miss a just-mounted SD/USB path. One retry, then wait
                // for the next epoch or a pane-root scan that resolved the directory.
                try {
                    Thread.sleep(MOUNT_RETRY_MS)
                } catch (_: InterruptedException) {
                    return@execute
                }
                if (!mountSnapshot.needsRefresh(epoch) || _volumeEpoch.value != epoch) return@execute
                val retryGeneration = scanGeneration.incrementAndGet()
                val retry = scanMounted(withStats = false)
                if (_volumeEpoch.value == epoch) {
                    publishMounted(epoch, retryGeneration, retry.mounted, retry.pending)
                }
            } finally {
                refreshQueued.set(false)
            }
        }
    }

    private fun publishMounted(
        epoch: Long,
        generation: Long,
        mounted: List<MountedVolume>,
        pending: Boolean,
    ) {
        synchronized(mountedLock) {
            val next = publishMountSnapshot(mountSnapshot, epoch, generation, mounted, pending)
            if (next == mountSnapshot) return
            mountSnapshot = next
            if (pending) pendingRetryAt = SystemClock.uptimeMillis() + MOUNT_RETRY_MS
            else pendingRetryAt = 0L
            _mountedVolumes.value = next.cached
        }
    }

    /**
     * StorageManager only unless [withStats] is set. A mounted volume whose directory
     * is not visible yet stays [MountScan.pending] so the snapshot is not sealed.
     */
    private fun scanMounted(withStats: Boolean): MountScan {
        var pending = false
        val mounted = ArrayList<MountedVolume>()
        val volumes = ArrayList<Volume>()
        for (volume in storageManager.storageVolumes) {
            if (volume.state != Environment.MEDIA_MOUNTED &&
                volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
            ) {
                continue
            }
            val dir = directoryOf(volume)
            if (dir == null) {
                pending = true
                continue
            }
            mounted += MountedVolume(
                path = dir.absolutePath,
                label = labelOf(volume),
                writable = volume.state != Environment.MEDIA_MOUNTED_READ_ONLY,
            )
            if (withStats) volumes += toVolume(volume, dir)
        }
        return MountScan(mounted, volumes, pending)
    }

    private fun labelOf(volume: StorageVolume): String =
        volume.getDescription(context)?.takeIf { it.isNotBlank() }
            ?: if (volume.isPrimary) "Internal storage" else "Storage"

    override fun volumes(): List<Volume> {
        val epoch = _volumeEpoch.value
        val generation = scanGeneration.incrementAndGet()
        val scan = scanMounted(withStats = true)
        // Drop the publish if a volume arrived mid-scan. Sealing the new epoch with
        // this result would hide that volume and permanent-delete its files.
        if (_volumeEpoch.value == epoch) {
            publishMounted(epoch, generation, scan.mounted, scan.pending)
        }
        return scan.volumes
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
        val roots = ArrayList<XEntry>(volumeEntries.size + specials.size + 5)
        roots += volumeEntries
        roots += TrashFileSystem.rootEntry(context.getString(R.string.recycle_bin))
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
        val label = labelOf(volume)
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

    private companion object {
        const val MOUNT_RETRY_MS = 300L
    }
}

private class MountScan(
    val mounted: List<MountedVolume>,
    val volumes: List<Volume>,
    val pending: Boolean,
)

internal data class MountPublish(
    val cached: List<MountedVolume> = emptyList(),
    val sealedEpoch: Long = Long.MIN_VALUE,
    /** Epoch of the scan that last won, including one that is still pending. */
    val observedEpoch: Long = Long.MIN_VALUE,
    val generation: Long = Long.MIN_VALUE,
    val pending: Boolean = false,
) {
    fun needsRefresh(epoch: Long): Boolean = pending || sealedEpoch != epoch
}

/**
 * A pending scan remembers [epoch] so a slower earlier scan cannot seal that
 * epoch without the new volume. While pending, roots already cached stay.
 */
internal fun publishMountSnapshot(
    current: MountPublish,
    epoch: Long,
    generation: Long,
    mounted: List<MountedVolume>,
    pending: Boolean,
): MountPublish {
    if (epoch < current.observedEpoch) return current
    if (epoch == current.observedEpoch && generation < current.generation) return current
    if (pending) {
        return MountPublish(
            cached = unionMountedVolumes(current.cached, mounted),
            sealedEpoch = current.sealedEpoch,
            observedEpoch = epoch,
            generation = generation,
            pending = true,
        )
    }
    return MountPublish(
        cached = mounted,
        sealedEpoch = epoch,
        observedEpoch = epoch,
        generation = generation,
        pending = false,
    )
}

internal fun unionMountedVolumes(
    current: List<MountedVolume>,
    incoming: List<MountedVolume>,
): List<MountedVolume> {
    // A pending scan must not keep a stale read-only flag for a volume it just saw.
    // Volumes missing from this scan stay, so a slower result cannot drop one.
    val merged = ArrayList<MountedVolume>(current.size + incoming.size)
    for (vol in current) {
        val update = incoming.firstOrNull { TrashPaths.samePath(it.path, vol.path) }
        merged += update ?: vol
    }
    for (vol in incoming) {
        if (merged.none { TrashPaths.samePath(it.path, vol.path) }) merged += vol
    }
    return merged
}
