package app.local1st.files.core.fs

import kotlinx.coroutines.flow.StateFlow

/** A storage volume shown as a pane root. */
data class Volume(
    val entry: XEntry,
    val label: String,
    val totalBytes: Long,
    val freeBytes: Long,
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
}
