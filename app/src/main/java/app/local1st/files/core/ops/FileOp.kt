package app.local1st.files.core.ops

import app.local1st.files.core.fs.XEntry

/** A long-running file operation submitted to the [OperationEngine]. */
sealed interface FileOp {
    val sources: List<XEntry>

    /** Copy or move [sources] into [destDir]. Copying out of an archive extracts it. */
    data class Copy(
        override val sources: List<XEntry>,
        val destDir: XEntry,
        val move: Boolean = false,
    ) : FileOp

    data class Delete(override val sources: List<XEntry>) : FileOp

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
