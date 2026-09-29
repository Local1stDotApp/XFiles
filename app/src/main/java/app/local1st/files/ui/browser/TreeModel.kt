package app.local1st.files.ui.browser

import androidx.compose.runtime.Immutable
import app.local1st.files.core.fs.XEntry

/**
 * Size and modification time of [id], or this list when [id] is absent or already current.
 * A folder takes only the time: its row's size is not a file length, and a root's may be its
 * volume's. An unchanged list is the same instance: the pane sort cache keys on that identity.
 */
internal fun List<XEntry>.withFileMetadata(id: String, size: Long, mtime: Long): List<XEntry> {
    val index = indexOfFirst { it.id == id }
    if (index < 0) return this
    val current = this[index]
    if (current.mtime == mtime && (current.isDir || current.size == size)) return this
    val updated = ArrayList(this)
    updated[index] = if (current.isDir) current.copy(mtime = mtime) else current.copy(size = size, mtime = mtime)
    return updated
}

/**
 * The same patch in every cached directory. A directory that does not contain [id] keeps its list.
 */
internal fun Map<String, List<XEntry>>.withFileMetadata(
    id: String,
    size: Long,
    mtime: Long,
): Map<String, List<XEntry>> {
    var changed: MutableMap<String, List<XEntry>>? = null
    for ((dir, kids) in this) {
        val updated = kids.withFileMetadata(id, size, mtime)
        if (updated !== kids) {
            if (changed == null) changed = LinkedHashMap(this)
            changed[dir] = updated
        }
    }
    return changed ?: this
}

/** Prefer a live listing's visible size over a stale hint once this directory has been listed. */
internal fun XEntry.withListedChildCount(listed: List<XEntry>?, showHidden: Boolean): XEntry {
    if (!isDir || badge != null) return this
    val n = if (listed != null) {
        listed.count { showHidden || !it.hidden }
    } else if (childCountHint < 0) {
        return this
    } else if (showHidden) {
        childCountHint
    } else {
        (childCountHint - hiddenChildCountHint).coerceAtLeast(0)
    }
    return if (childCountHint == n) this else copy(childCountHint = n)
}

/** One visible row of a pane's flattened tree. */
@Immutable
data class TreeNode(
    val entry: XEntry,
    /**
     * Position-unique list key. An entry id alone is not unique across the whole tree:
     * a removable volume root (`file:///storage/UUID`) also appears as a child of `/storage`
     * under the filesystem `Root`, so keying a LazyColumn by id would crash. Qualifying with
     * the parent container id disambiguates (a name is unique within one parent).
     */
    val key: String,
    val depth: Int,
    val expanded: Boolean,
    val loading: Boolean,
    /**
     * Ancestor guide lines: guides[d] is true when a vertical line should be drawn
     * at depth d because that ancestor has more siblings below.
     */
    val guides: List<Boolean>,
    val isLastChild: Boolean,
    val error: String? = null,
)

@Immutable
data class PaneUiState(
    val nodes: List<TreeNode> = emptyList(),
    val selection: Set<String> = emptySet(),
    val focusedDirId: String? = null,
    val loadingRoots: Boolean = true,
    /** Initial LazyColumn row, available only with the matching settled tree snapshot. */
    val initialScrollIndex: Int? = null,
    /** Internal barrier used to publish a fully restored tree and its initial row together. */
    val treeVersion: Long = 0,
    /** False while saved off-path branches are still reconciling behind the first frame. */
    val startupSettled: Boolean = false,
    /** True only while rows come from the visual cache and must not be used for file actions. */
    val snapshotOnly: Boolean = false,
)
