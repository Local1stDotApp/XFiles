package app.local1st.files.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import kotlinx.coroutines.flow.first

/**
 * Shared height of a breadcrumb pill and the compact screen's other-pane target chip,
 * so a single header row can keep them on one mid-line.
 */
val CrumbBarHeight = 40.dp

/**
 * One browser pane: breadcrumb bar + flattened tree list.
 * The whole X-plore signature view.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun PaneView(
    controller: PaneController,
    active: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (XEntry) -> Unit,
    onEntryMenu: (XEntry) -> Unit,
    onInitialLayoutReady: (treeVersion: Long) -> Unit,
    breadcrumbAlignment: Alignment = Alignment.TopStart,
    headerStartPadding: Dp = 6.dp,
    headerEndPadding: Dp = 6.dp,
    headerOverlay: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val initialScrollIndex = state.initialScrollIndex
    if (initialScrollIndex == null) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = if (active) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
                PaneHeader(
                    focusedDirId = state.focusedDirId,
                    active = active,
                    breadcrumbAlignment = breadcrumbAlignment,
                    headerStartPadding = headerStartPadding,
                    headerEndPadding = headerEndPadding,
                    headerOverlay = headerOverlay,
                    onCrumbClick = { id ->
                        onActivate()
                        controller.revealPath(id)
                    },
                )
            }
        }
        return
    }

    // This state is first created only after PaneController has published the fully restored tree.
    // LazyColumn therefore lays out the restored row on its first frame; there is no row-0 frame
    // followed by a corrective scroll (animated or otherwise).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
    val currentOnInitialLayoutReady by rememberUpdatedState(onInitialLayoutReady)
    // NavDisplay removes covered destinations from composition. Save these booleans with the
    // browser entry so returning from Settings/Search does not replay the lightweight-row phase.
    var richRowsEnabled by rememberSaveable(controller) { mutableStateOf(false) }
    var itemAnimationsEnabled by rememberSaveable(controller) { mutableStateOf(false) }

    // A measured lightweight list is already a valid first frame. Remove the startup cover now;
    // thumbnail painters and animation nodes are enabled only after that frame is safely visible.
    LaunchedEffect(controller, listState, state.treeVersion) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        currentOnInitialLayoutReady(state.treeVersion)
        if (!richRowsEnabled) {
            withFrameNanos { }
            withFrameNanos { }
            richRowsEnabled = true
        }
    }

    // A background restore may insert saved off-path branches. Let stable item keys preserve the
    // scroll anchor, and enable placement animation only on a later frame after reconciliation.
    LaunchedEffect(state.startupSettled, richRowsEnabled) {
        itemAnimationsEnabled = false
        if (state.startupSettled && richRowsEnabled) {
            withFrameNanos { }
            itemAnimationsEnabled = true
        }
    }

    LaunchedEffect(controller, listState) {
        controller.scrollTo.collect { request ->
            val index = controller.state.value.nodes.indexOfFirst { it.entry.id == request.id }
            if (index < 0) return@collect
            if (request.animate) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (active) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Rows scroll edge-to-edge under the status bar and the floating breadcrumb;
            // the top inset only keeps row 0 initially clear of both.
            // IgnoringVisibility: the video player hides the system bars, and reacting
            // to that would reflow (and permanently shift) this list on every return.
            val statusPad = WindowInsets.statusBarsIgnoringVisibility
                .asPaddingValues().calculateTopPadding()

            if (state.loadingRoots && state.nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = statusPad + 8.dp + CrumbBarHeight,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = state.nodes.size,
                        key = { state.nodes[it].key },
                    ) { index ->
                        val node = state.nodes[index]
                        EntryRow(
                            node = node,
                            selected = node.entry.id in state.selection,
                            focused = node.entry.id == state.focusedDirId,
                            onClick = {
                                onActivate()
                                onOpenEntry(node.entry)
                            },
                            onLongClick = {
                                onActivate()
                                onEntryMenu(node.entry)
                            },
                            onToggleSelect = {
                                onActivate()
                                controller.toggleSelect(node.entry)
                            },
                            enabled = !state.snapshotOnly,
                            richContent = richRowsEnabled,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .then(
                                    if (itemAnimationsEnabled) Modifier.animateItem()
                                    else Modifier,
                                ),
                        )
                    }
                }
            }

            PaneHeader(
                focusedDirId = state.focusedDirId,
                active = active,
                breadcrumbAlignment = breadcrumbAlignment,
                headerStartPadding = headerStartPadding,
                headerEndPadding = headerEndPadding,
                headerOverlay = headerOverlay,
                onCrumbClick = { id ->
                    onActivate()
                    controller.revealPath(id)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.PaneHeader(
    focusedDirId: String?,
    active: Boolean,
    breadcrumbAlignment: Alignment,
    headerStartPadding: Dp,
    headerEndPadding: Dp,
    headerOverlay: (@Composable () -> Unit)?,
    onCrumbClick: (String) -> Unit,
) {
    // One row so the compact target chip only takes the width it needs. Overlaying two
    // independently aligned pills forced a worst-case hole on the opposite side.
    val chipOnStart = headerOverlay != null && breadcrumbAlignment == Alignment.TopEnd
    val chipOnEnd = headerOverlay != null && !chipOnStart
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(
                start = headerStartPadding,
                top = 4.dp,
                end = headerEndPadding,
            ),
    ) {
        if (chipOnStart) {
            headerOverlay?.invoke()
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = if (chipOnStart) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            BreadcrumbBar(
                focusedDirId = focusedDirId,
                active = active,
                onCrumbClick = onCrumbClick,
                modifier = Modifier.wrapContentWidth(
                    align = if (chipOnStart) Alignment.End else Alignment.Start,
                    unbounded = false,
                ),
            )
        }
        if (chipOnEnd) {
            Spacer(Modifier.width(8.dp))
            headerOverlay?.invoke()
        }
    }
}

/**
 * Floating breadcrumb pill; the list scrolls underneath it.
 *
 * It shares a row (and [CrumbBarHeight] mid-line) with the compact target chip. A floor
 * rather than a fixed height: at large font scales the pill grows instead of clipping
 * the trail.
 */
@Composable
private fun BreadcrumbBar(
    focusedDirId: String?,
    active: Boolean,
    onCrumbClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val crumbs = crumbsFor(focusedDirId)
    Surface(
        shape = RoundedCornerShape(CrumbBarHeight / 2),
        // On wide screens both panes are visible: the inactive pane's breadcrumb is the target.
        color = if (active) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
        },
        modifier = modifier.defaultMinSize(
            minWidth = CrumbBarHeight,
            minHeight = CrumbBarHeight,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState(), reverseScrolling = true)
                .padding(horizontal = 12.dp),
        ) {
            if (crumbs.isEmpty()) {
                Text(
                    "XFiles",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Padded exactly like a crumb: the fallback and a real trail have to
                    // measure alike at every font scale, or the pill would resize (and jolt
                    // the list under it) every time the tree collapses back to the root.
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
            crumbs.forEachIndexed { index, (id, name) ->
                if (index > 0) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = when {
                        index == crumbs.lastIndex && active -> MaterialTheme.colorScheme.primary
                        index == crumbs.lastIndex -> MaterialTheme.colorScheme.onSecondaryContainer
                        !active -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clickable { onCrumbClick(id) }
                        .padding(vertical = 4.dp, horizontal = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun crumbsFor(focusedDirId: String?): List<Pair<String, String>> {
    focusedDirId ?: return emptyList()
    val chain = generateSequence(focusedDirId) { XId.parent(it) }.toList().reversed()
    return chain.map { id ->
        val raw = id.substringAfter("://")
        val name = when (raw) {
            "@user" -> stringResource(R.string.installed_apps)
            "@system" -> stringResource(R.string.system_apps)
            else -> raw.trimEnd('/').substringAfterLast('/')
                .substringAfterLast(XId.ARCHIVE_SEP)
                .ifEmpty { if (id.startsWith(XId.SCHEME_APPS)) stringResource(R.string.apps) else "/" }
        }
        id to name
    }
}
