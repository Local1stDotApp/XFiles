package app.local1st.files.core.ops

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.TrashPaths
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId

/** A long-running file operation submitted to the [OperationEngine]. */
sealed interface FileOp {
    val sources: List<XEntry>

    /** Copy or move [sources] into [destDir]. Copying out of an archive extracts it. */
    data class Copy(
        override val sources: List<XEntry>,
        val destDir: XEntry,
        val move: Boolean = false,
    ) : FileOp

    /**
     * Delete [sources]. A local file on a writable volume is moved to that volume's
     * Recycle Bin unless [permanent] is set. [permanent] is only the user's explicit
     * choice. [mustTrash] means every source was confirmed for the bin.
     * [trashableIds] are the mixed-confirm ids that were trashable then; those
     * must not be unlinked if a later mount list cannot take them.
     */
    data class Delete(
        override val sources: List<XEntry>,
        val permanent: Boolean = false,
        val mustTrash: Boolean = false,
        val trashableIds: Set<String> = emptySet(),
    ) : FileOp

    /** Put Recycle Bin items back at their original paths. */
    data class Restore(override val sources: List<XEntry>) : FileOp

    /** Pack [sources] into a new zip named [archiveName] inside [destDir]. */
    data class Compress(
        override val sources: List<XEntry>,
        val destDir: XEntry,
        val archiveName: String,
    ) : FileOp

    /** Extract [archive]'s contents into [destDir] (parallel fast path for zip/apk/jar). */
    data class Extract(
        val archive: XEntry,
        val destDir: XEntry,
    ) : FileOp {
        override val sources: List<XEntry> get() = listOf(archive)
    }
}

enum class OpState { SCANNING, RUNNING, AWAITING_CONFLICT, DONE, FAILED, CANCELLED }

data class OpProgress(
    val title: String,
    val state: OpState = OpState.SCANNING,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val totalItems: Int = 0,
    val doneItems: Int = 0,
    val currentItem: String = "",
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        else if (totalItems > 0) doneItems.toFloat() / totalItems
        else 0f
}

/** Raised to the UI when a destination name already exists. */
data class Conflict(
    val source: XEntry,
    val existingName: String,
)

enum class ConflictChoice { SKIP, OVERWRITE, RENAME }

data class ConflictResolution(
    val choice: ConflictChoice,
    val applyToAll: Boolean = false,
)

/**
 * Move copies then deletes the source. Pane roots and virtual nodes cannot be deleted as a
 * unit (SAF location roots, volumes, `/`, apps), so they are copy-only.
 */
internal fun canMoveSource(entry: XEntry): Boolean =
    entry.canWrite &&
        (entry.kind == EntryKind.DIR ||
            entry.kind == EntryKind.FILE ||
            entry.kind == EntryKind.ARCHIVE)

/** In-app text editing is a local-file path; SAF/root entries open as a read-only stream. */
internal fun canEditCreatedTextFile(entry: XEntry): Boolean =
    entry.scheme == XId.SCHEME_FILE && entry.canWrite

/**
 * Folder name for an extract. Listings hide a volume's bin, and another spelling
 * is the same directory, so that name is never reused.
 */
internal fun uniqueExtractFolderName(
    desired: String,
    listed: Collection<String>,
    volumeRoot: Boolean,
    caseInsensitive: Boolean = false,
): String {
    val taken: MutableSet<String> = if (caseInsensitive) {
        java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)
    } else {
        HashSet()
    }
    taken += listed
    if (volumeRoot) taken += TrashPaths.DIR_NAME
    var name = desired
    var i = 1
    while (name in taken || (volumeRoot && name.equals(TrashPaths.DIR_NAME, ignoreCase = true))) {
        name = "$desired ($i)".also { i++ }
    }
    return name
}
