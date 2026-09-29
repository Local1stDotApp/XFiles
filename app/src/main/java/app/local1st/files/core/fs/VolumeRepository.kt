package app.local1st.files.core.fs

import kotlinx.coroutines.flow.StateFlow

/** A storage volume shown as a pane root. */
data class Volume(
    val entry: XEntry,
    val label: String,
    val totalBytes: Long,
    val freeBytes: Long,
)

/**
 * A mounted volume without disk stats. Trash checks and composition read this
 * instead of calling StatFs on every entry or every frame.
 */
data class MountedVolume(
    val path: String,
    val label: String,
    val writable: Boolean,
)

/** Provides the roots each pane starts with. */
interface RootsRepository {
    /** Storage volumes (internal, SD, USB OTG) currently mounted. */
    fun volumes(): List<Volume>

    /** Full root list for a pane: volumes + special roots (app manager, ...). */
    fun paneRoots(): List<XEntry>

    /**
     * Bumps when a storage volume is mounted, unmounted, or changes state so the
     * browser can rebuild pane roots without a manual refresh.
     */
    val volumeEpoch: StateFlow<Long>

    /** Last mount scan. Updates when [volumeEpoch] changes. Does not call [android.os.StatFs]. */
    val mountedVolumes: StateFlow<List<MountedVolume>>

    /** Cache only. Schedules a background refresh when the cache is stale. */
    fun peekMountedVolumes(): List<MountedVolume>

    /** One mount scan without [android.os.StatFs] when the cache is stale. Not for composition. */
    fun currentMountedVolumes(): List<MountedVolume>

    /**
     * Like [currentMountedVolumes], but fails when a mounted volume still has no
     * directory. Delete must not treat that unknown volume as "not trashable".
     */
    fun requireMountedVolumes(): List<MountedVolume>

    /**
     * True while a mounted volume still has no directory. Callers that already
     * have a path on a resolved volume must not wait on the unknown one.
     */
    fun hasUnresolvedVolume(): Boolean
}
