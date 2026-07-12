package com.xfiles.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
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
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xfiles.ui.browser.PaneView
import com.xfiles.ui.dialogs.DialogRequest
import com.xfiles.ui.dialogs.MainDialogs
import com.xfiles.ui.dialogs.OpsHost
import com.xfiles.ui.search.SearchOverlay
import com.xfiles.ui.settings.SettingsOverlay
import com.xfiles.ui.viewer.ViewerHost

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val activePane by vm.activePane.collectAsStateWithLifecycle()
    val pane0State by vm.panes[0].state.collectAsStateWithLifecycle()
    val pane1State by vm.panes[1].state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val activeState = if (activePane == 0) pane0State else pane1State
    val selectionCount = activeState.selection.size

    LaunchedEffect(vm) {
        vm.snackbar.collect { snackbarHostState.showSnackbar(it) }
    }

    BackHandler(enabled = selectionCount > 0) {
        vm.activeCtrl.clearSelection()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("XFiles") },
                actions = {
                    IconButton(onClick = { vm.openSearch() }) {
                        Icon(Icons.Outlined.Search, "Search")
                    }
                    IconButton(onClick = { vm.dialog.value = DialogRequest.SortOptions }) {
                        Icon(Icons.Outlined.Sort, "Sort")
                    }
                    IconButton(onClick = { vm.showSettings.value = true }) {
                        Icon(Icons.Outlined.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
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
                                contentPadding = listPadding,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                } else {
                    val pagerState = rememberPagerState(initialPage = activePane) { 2 }
                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }.collect { vm.setActivePane(it) }
                    }
                    LaunchedEffect(activePane) {
                        if (pagerState.currentPage != activePane &&
                            !pagerState.isScrollInProgress
                        ) {
                            pagerState.animateScrollToPage(activePane)
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val pane = vm.panes[page]
                        PaneView(
                            controller = pane,
                            active = activePane == page,
                            onActivate = { vm.setActivePane(page) },
                            onOpenEntry = { vm.openEntry(pane, it) },
                            onEntryMenu = { vm.dialog.value = DialogRequest.EntryMenu(it) },
                            contentPadding = listPadding,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            // Signature X-plore action bar, reimagined as an Expressive floating toolbar.
            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-24).dp),
                content = {
                    AnimatedContent(
                        targetState = selectionCount > 0,
                        label = "toolbar",
                    ) { hasSelection ->
                        Row {
                            if (hasSelection) {
                                IconButton(onClick = { vm.activeCtrl.clearSelection() }) {
                                    Icon(Icons.Outlined.Close, "Clear selection")
                                }
                                Text(
                                    "$selectionCount",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                                IconButton(onClick = { vm.copySelection(move = false) }) {
                                    Icon(Icons.Outlined.ContentCopy, "Copy to other pane")
                                }
                                IconButton(onClick = { vm.copySelection(move = true) }) {
                                    Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to other pane")
                                }
                                IconButton(onClick = { vm.requestDelete() }) {
                                    Icon(Icons.Outlined.Delete, "Delete")
                                }
                                IconButton(onClick = { vm.requestCompress() }) {
                                    Icon(Icons.Outlined.Archive, "Zip")
                                }
                                IconButton(onClick = { vm.shareSelection() }) {
                                    Icon(Icons.Outlined.Share, "Share")
                                }
                            } else {
                                IconButton(onClick = { vm.requestNewFolder() }) {
                                    Icon(Icons.Outlined.CreateNewFolder, "New folder")
                                }
                                IconButton(onClick = { vm.openSearch() }) {
                                    Icon(Icons.Outlined.Search, "Search")
                                }
                                IconButton(onClick = {
                                    vm.setActivePane(1 - activePane)
                                }) {
                                    Icon(Icons.Outlined.SwapHoriz, "Switch pane")
                                }
                                IconButton(onClick = { vm.activeCtrl.refreshAllExpanded() }) {
                                    Icon(Icons.Outlined.Refresh, "Refresh")
                                }
                                IconButton(onClick = {
                                    vm.activeCtrl.focusedDirEntry()
                                        ?.let { vm.dialog.value = DialogRequest.EntryMenu(it) }
                                }) {
                                    Icon(Icons.Outlined.MoreVert, "More")
                                }
                            }
                        }
                    }
                },
            )

            OpsHost(vm)
        }
    }

    MainDialogs(vm)
    ViewerHost(vm)
    SearchOverlay(vm)
    SettingsOverlay(vm)
}
