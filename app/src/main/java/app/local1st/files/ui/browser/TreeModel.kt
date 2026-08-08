package app.local1st.files.ui.browser

import androidx.compose.runtime.Immutable
import app.local1st.files.core.fs.XEntry

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
