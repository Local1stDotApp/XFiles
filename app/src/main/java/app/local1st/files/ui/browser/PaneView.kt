package app.local1st.files.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId

/**
 * One browser pane: breadcrumb bar + flattened tree list.
 * The whole X-plore signature view.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        Column(Modifier.fillMaxSize()) {
            BreadcrumbBar(
                focusedDirId = state.focusedDirId,
                active = active,
                onCrumbClick = { id ->
                    onActivate()
                    controller.revealPath(id)
                },
            )

            if (state.loadingRoots && state.nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
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
        }
    }
}

@Composable
private fun BreadcrumbBar(
    focusedDirId: String?,
    active: Boolean,
    onCrumbClick: (String) -> Unit,
) {
    val crumbs = remember(focusedDirId) { crumbsFor(focusedDirId) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState(), reverseScrolling = true)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (crumbs.isEmpty()) {
            Text(
                "XFiles",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun crumbsFor(focusedDirId: String?): List<Pair<String, String>> {
    focusedDirId ?: return emptyList()
    val chain = generateSequence(focusedDirId) { XId.parent(it) }.toList().reversed()
    return chain.map { id ->
        val raw = id.substringAfter("://")
        val name = when (raw) {
            "@user" -> "Installed"
            "@system" -> "System"
            else -> raw.trimEnd('/').substringAfterLast('/')
                .substringAfterLast(XId.ARCHIVE_SEP)
                .ifEmpty { if (id.startsWith(XId.SCHEME_APPS)) "Apps" else "/" }
        }
        id to name
    }
}
