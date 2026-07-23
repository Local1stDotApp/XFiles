package app.local1st.files.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId

/**
 * The box the breadcrumb pill and the floating settings button both occupy. Sharing one
 * size is what lines them up: same top inset + same height means the same mid-line, with
 * no measuring or offsets between the two composables.
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
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(controller) {
        controller.scrollTo.collect { id ->
            val index = controller.state.value.nodes.indexOfFirst { it.entry.id == id }
            if (index >= 0) listState.animateScrollToItem(index)
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
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .animateItem(),
                        )
                    }
                }
            }

            BreadcrumbBar(
                focusedDirId = state.focusedDirId,
                active = active,
                onCrumbClick = { id ->
                    onActivate()
                    controller.revealPath(id)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    // The end inset keeps a long trail clear of the floating settings button.
                    .padding(start = 6.dp, top = 4.dp, end = 60.dp),
            )
        }
    }
}

/**
 * Floating breadcrumb pill; the list scrolls underneath it.
 *
 * It occupies the same [CrumbBarHeight] box as the settings button opposite it, so the two
 * share a mid-line by construction. A floor rather than a fixed height: at large font
 * scales the pill grows instead of clipping the trail.
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
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
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = when {
                        index == crumbs.lastIndex && active -> MaterialTheme.colorScheme.primary
                        index == crumbs.lastIndex -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clickable { onCrumbClick(id) }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
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
