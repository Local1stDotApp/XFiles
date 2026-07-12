package com.xfiles.ui.browser

import androidx.compose.runtime.Immutable
import com.xfiles.core.fs.XEntry

/** One visible row of a pane's flattened tree. */
@Immutable
data class TreeNode(
    val entry: XEntry,
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
)
