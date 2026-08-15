package app.local1st.files.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.ui.browser.CrumbBarHeight
import app.local1st.files.ui.browser.PaneView
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.dialogs.DialogRequest

private val CompactTargetChipMaxWidth = 96.dp
private val CompactIosChevronSize = 12.dp

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val sessionReady by vm.sessionReady.collectAsStateWithLifecycle()
    val activePane by vm.activePane.collectAsStateWithLifecycle()
    val activeState by vm.panes[activePane].state.collectAsStateWithLifecycle()
    val otherPaneController = vm.panes[1 - activePane]
    val otherPaneState by otherPaneController.state.collectAsStateWithLifecycle()
    val otherPaneDestination = otherPaneController.focusedDirEntry()
    val otherPaneName = paneLocationName(otherPaneDestination, otherPaneState.focusedDirId)
    val otherPanePath = paneLocationPath(otherPaneDestination, otherPaneState.focusedDirId)
    val canUseOtherPane = isFileOperationDestination(otherPaneDestination)
    var initiallyLaidOutPanes by remember(vm) { mutableStateOf<Set<Int>>(emptySet()) }
    var startupContentReady by rememberSaveable(vm) {
        mutableStateOf(sessionReady && activeState.snapshotOnly)
    }
    val wideLayout = LocalConfiguration.current.screenWidthDp >= 700

    val selectionCount = activeState.selection.size
    val selectedFiles = if (selectionCount > 0) {
        vm.activeCtrl.selectionEntries().filter { !it.isDir }
    } else {
        emptyList()
    }
    val canShareSelection = selectedFiles.isNotEmpty() && selectedFiles.all { it.localPath != null }
    val unavailableDestinationLabel = stringResource(R.string.cannot_write, otherPaneName)
    val copyTargetLabel = if (canUseOtherPane) {
        "${stringResource(R.string.copy_to_title)} $otherPaneName"
    } else {
        unavailableDestinationLabel
    }
    val moveTargetLabel = if (canUseOtherPane) {
        "${stringResource(R.string.move_to_title)} $otherPaneName"
    } else {
        unavailableDestinationLabel
    }
    val compressTargetLabel = if (canUseOtherPane) {
        "${stringResource(R.string.compress_to)} $otherPaneName"
    } else {
        unavailableDestinationLabel
    }

    BackHandler(enabled = selectionCount > 0) {
        vm.activeCtrl.clearSelection()
    }

    // No top app bar at all: the panes extend under the status bar, and the few former
    // top-bar actions live elsewhere (search and Settings in the bottom toolbar). On phones the
    // freed top-end slot exposes the otherwise hidden destination pane. The explicit background
    // paints the pane gutters and rounded-corner gaps that Scaffold used to cover.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val listPadding = PaddingValues(bottom = 120.dp)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 700.dp
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    vm.panes.forEachIndexed { index, pane ->
                        PaneView(
                            controller = pane,
                            active = activePane == index,
                            onActivate = { vm.setActivePane(index) },
                            onOpenEntry = { vm.openEntry(pane, it) },
                            onEntryMenu = { vm.dialog.value = DialogRequest.EntryMenu(it) },
                            onInitialLayoutReady = { version ->
                                initiallyLaidOutPanes = initiallyLaidOutPanes + index
                                vm.onPaneInitialLayoutReady(index, version)
                            },
                            contentPadding = listPadding,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            } else {
                val pagerState = rememberPagerState(initialPage = activePane) { 2 }
                LaunchedEffect(pagerState, sessionReady) {
                    if (!sessionReady) return@LaunchedEffect
                    snapshotFlow { pagerState.currentPage }.collect { vm.setActivePane(it) }
                }
                LaunchedEffect(activePane) {
                    if (pagerState.currentPage != activePane &&
                        !pagerState.isScrollInProgress
                    ) {
                        if (sessionReady) pagerState.animateScrollToPage(activePane)
                        else pagerState.scrollToPage(activePane)
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    // The inactive pane's data restores off the critical path, but composing its
                    // full tree here would still charge that work to phone startup. Pager will
                    // compose it naturally when the user begins to swipe toward it.
                    beyondViewportPageCount = 0,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val pane = vm.panes[page]
                    PaneView(
                        controller = pane,
                        active = activePane == page,
                        onActivate = { vm.setActivePane(page) },
                        onOpenEntry = { vm.openEntry(pane, it) },
                        onEntryMenu = { vm.dialog.value = DialogRequest.EntryMenu(it) },
                        onInitialLayoutReady = { version ->
                            initiallyLaidOutPanes = initiallyLaidOutPanes + page
                            vm.onPaneInitialLayoutReady(page, version)
                            if (page == activePane) startupContentReady = true
                        },
                        breadcrumbAlignment = if (page == 0) {
                            Alignment.TopStart
                        } else {
                            Alignment.TopEnd
                        },
                        headerOverlay = {
                            val targetPane = 1 - page
                            val targetController = vm.panes[targetPane]
                            val targetState = if (targetPane == activePane) {
                                activeState
                            } else {
                                otherPaneState
                            }
                            val targetDestination = targetController.focusedDirEntry()
                            OtherPaneTargetChip(
                                name = paneLocationName(
                                    targetDestination,
                                    targetState.focusedDirId,
                                ),
                                path = paneLocationPath(
                                    targetDestination,
                                    targetState.focusedDirId,
                                ),
                                ready = targetDestination != null,
                                writable = isFileOperationDestination(targetDestination),
                                activePane = page,
                                onClick = { vm.setActivePane(targetPane) },
                            )
                        },
                        contentPadding = listPadding,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // The list scrolls under the transparent status bar; this gradient keeps the
        // clock and icons readable over whatever content passes beneath.
        // All insets on this screen use the IgnoringVisibility variants: the video
        // player hides the system bars for its own window, and the plain insets would
        // collapse to 0 and reflow this whole page under it — every trip through a
        // video would visibly shift the browser.
        val statusPad = WindowInsets.statusBarsIgnoringVisibility
            .asPaddingValues().calculateTopPadding()
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusPad + 16.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Signature X-plore action bar, reimagined as an Expressive floating toolbar.
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .offset(y = (-24).dp),
            content = {
                AnimatedContent(
                    targetState = selectionCount > 0,
                    label = "toolbar",
                ) { hasSelection ->
                    Row {
                        if (hasSelection) {
                            TooltipIconButton(stringResource(R.string.clear), Icons.Outlined.Close) {
                                vm.activeCtrl.clearSelection()
                            }
                            Text(
                                "$selectionCount",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            TooltipIconButton(
                                label = copyTargetLabel,
                                icon = Icons.Outlined.ContentCopy,
                                enabled = canUseOtherPane,
                            ) {
                                vm.copySelection(move = false)
                            }
                            TooltipIconButton(
                                label = moveTargetLabel,
                                icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                                enabled = canUseOtherPane,
                            ) {
                                vm.copySelection(move = true)
                            }
                            TooltipIconButton(stringResource(R.string.delete), Icons.Outlined.Delete) { vm.requestDelete() }
                            TooltipIconButton(
                                label = compressTargetLabel,
                                icon = Icons.Outlined.Archive,
                                enabled = canUseOtherPane,
                            ) { vm.requestCompress() }
                            TooltipIconButton(
                                label = stringResource(
                                    if (canShareSelection) R.string.share
                                    else R.string.share_requires_local_files,
                                ),
                                icon = Icons.Outlined.Share,
                                enabled = canShareSelection,
                            ) { vm.shareSelection() }
                        } else {
                            TooltipIconButton(stringResource(R.string.new_folder), Icons.Outlined.CreateNewFolder) {
                                vm.requestNewFolder()
                            }
                            TooltipIconButton(
                                stringResource(R.string.new_text_file),
                                Icons.AutoMirrored.Outlined.NoteAdd,
                            ) {
                                vm.requestNewTextFile()
                            }
                            TooltipIconButton(
                                stringResource(R.string.search),
                                Icons.Outlined.Search,
                                onClick = dropUnlessResumed { vm.openSearch() },
                            )
                            TooltipIconButton(
                                stringResource(R.string.settings),
                                Icons.Outlined.Settings,
                                onClick = dropUnlessResumed { vm.openSettings() },
                            )
                            TooltipIconButton(stringResource(R.string.refresh), Icons.Outlined.Refresh) {
                                vm.activeCtrl.refreshAllExpanded()
                            }
                            TooltipIconButton(stringResource(R.string.more), Icons.Outlined.MoreVert) {
                                vm.activeCtrl.focusedDirEntry()
                                    ?.let { vm.dialog.value = DialogRequest.EntryMenu(it) }
                            }
                        }
                    }
                }
            },
        )

        val requiredPanes = if (wideLayout) vm.panes.indices else listOf(activePane)
        val currentLayoutReady = requiredPanes.all { index ->
            index in initiallyLaidOutPanes
        }
        LaunchedEffect(sessionReady, currentLayoutReady) {
            if (sessionReady && currentLayoutReady) startupContentReady = true
        }
        if (!sessionReady || !startupContentReady) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherPaneTargetChip(
    name: String,
    path: String,
    ready: Boolean,
    writable: Boolean,
    activePane: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val switchLabel = "${stringResource(R.string.switch_pane)}: $path"
    val unavailableLabel = stringResource(R.string.cannot_write, name)
    val tooltip = if (ready && !writable) {
        "$switchLabel · $unavailableLabel"
    } else {
        switchLabel
    }
    val containerColor = when {
        !ready -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
        writable -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
    }
    val contentColor = when {
        !ready -> MaterialTheme.colorScheme.onSurfaceVariant
        writable -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    // iOS nav chevrons: thin, and they sit on the side the tap will go.
    val pointingRight = activePane == 0
    val chevron = if (pointingRight) {
        Icons.AutoMirrored.Outlined.ArrowForwardIos
    } else {
        Icons.AutoMirrored.Outlined.ArrowBackIos
    }

    // Alignment belongs to this direct Box child; TooltipBox does not expose a Box scope parent.
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Below,
            ),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            // Keep the visible pill aligned with the 40dp breadcrumb. Compose still expands the
            // actual touch hit area to the platform's 48dp accessibility minimum.
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides CrumbBarHeight,
            ) {
                Surface(
                    onClick = onClick,
                    shape = MaterialTheme.shapes.extraLarge,
                    color = containerColor,
                    contentColor = contentColor,
                    modifier = Modifier
                        .widthIn(max = CompactTargetChipMaxWidth)
                        .semantics(mergeDescendants = true) {
                            contentDescription = switchLabel
                            if (ready && !writable) {
                                stateDescription = unavailableLabel
                            }
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .defaultMinSize(minHeight = CrumbBarHeight)
                            .padding(
                                start = if (pointingRight) 10.dp else 6.dp,
                                top = 4.dp,
                                end = if (pointingRight) 6.dp else 10.dp,
                                bottom = 4.dp,
                            ),
                    ) {
                        if (!pointingRight) {
                            Icon(
                                chevron,
                                contentDescription = null,
                                modifier = Modifier.size(CompactIosChevronSize),
                            )
                        }
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .weight(1f, fill = false),
                        )
                        if (pointingRight) {
                            Icon(
                                chevron,
                                contentDescription = null,
                                modifier = Modifier.size(CompactIosChevronSize),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun paneLocationName(destination: XEntry?, focusedDirId: String?): String {
    destination?.name?.takeIf { it.isNotBlank() }?.let { return it }
    val id = focusedDirId ?: return "…"
    val raw = id.substringAfter("://")
    return when (raw) {
        "@user" -> stringResource(R.string.installed_apps)
        "@system" -> stringResource(R.string.system_apps)
        else -> raw.trimEnd('/').substringAfterLast('/')
            .substringAfterLast(XId.ARCHIVE_SEP)
            .ifEmpty {
                if (id.startsWith(XId.SCHEME_APPS)) stringResource(R.string.apps) else "/"
            }
    }
}

private fun paneLocationPath(destination: XEntry?, focusedDirId: String?): String {
    val id = destination?.id ?: focusedDirId ?: return "…"
    val path = id.substringAfter("://")
    return if (id.startsWith("${XId.SCHEME_ROOT}://")) "root:$path" else path.ifBlank { "/" }
}
