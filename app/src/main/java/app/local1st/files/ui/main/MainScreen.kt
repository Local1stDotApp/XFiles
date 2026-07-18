package app.local1st.files.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.ui.appinfo.AppInfoOverlay
import app.local1st.files.ui.browser.PaneView
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.dialogs.DestinationPicker
import app.local1st.files.ui.dialogs.DialogRequest
import app.local1st.files.ui.dialogs.MainDialogs
import app.local1st.files.ui.dialogs.OpsHost
import app.local1st.files.ui.search.SearchOverlay
import app.local1st.files.ui.settings.SettingsOverlay
import app.local1st.files.ui.viewer.ViewerHost

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
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

    // Without POST_NOTIFICATIONS the foreground-service progress notification is silently
    // hidden on Android 13+, so ask for it once.
    RequestNotificationPermission()

    BackHandler(enabled = selectionCount > 0) {
        vm.activeCtrl.clearSelection()
    }

    // No top app bar at all: the panes extend under the status bar, and the few former
    // top-bar actions live elsewhere (search in the bottom toolbar, sorting in Settings,
    // settings as a floating button). The explicit background paints the pane gutters
    // and rounded-corner gaps that Scaffold used to cover (the window itself is black).
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

        // A compact 40dp circle on the breadcrumb pill's top line: a full 48dp
        // IconButton both out-sizes the pill and bleeds into the first list row.
        // (align must sit on a direct Box child — TooltipBox swallows it.)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(top = 4.dp, end = 8.dp),
        ) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Settings") } },
                state = rememberTooltipState(),
            ) {
                Surface(
                    onClick = { vm.showSettings.value = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

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
                            TooltipIconButton("Clear", Icons.Outlined.Close) {
                                vm.activeCtrl.clearSelection()
                            }
                            Text(
                                "$selectionCount",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            TooltipIconButton("Copy to…", Icons.Outlined.ContentCopy) {
                                vm.copySelection(move = false)
                            }
                            TooltipIconButton("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) {
                                vm.copySelection(move = true)
                            }
                            TooltipIconButton("Delete", Icons.Outlined.Delete) { vm.requestDelete() }
                            TooltipIconButton("Zip", Icons.Outlined.Archive) { vm.requestCompress() }
                            TooltipIconButton("Share", Icons.Outlined.Share) { vm.shareSelection() }
                        } else {
                            TooltipIconButton("New folder", Icons.Outlined.CreateNewFolder) {
                                vm.requestNewFolder()
                            }
                            TooltipIconButton("Search", Icons.Outlined.Search) { vm.openSearch() }
                            TooltipIconButton("Switch pane", Icons.Outlined.SwapHoriz) {
                                vm.setActivePane(1 - activePane)
                            }
                            TooltipIconButton("Refresh", Icons.Outlined.Refresh) {
                                vm.activeCtrl.refreshAllExpanded()
                            }
                            TooltipIconButton("More", Icons.Outlined.MoreVert) {
                                vm.activeCtrl.focusedDirEntry()
                                    ?.let { vm.dialog.value = DialogRequest.EntryMenu(it) }
                            }
                        }
                    }
                }
            },
        )

        OpsHost(vm)

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
        )
    }

    MainDialogs(vm)
    DestinationPicker(vm)
    ViewerHost(vm)
    SearchOverlay(vm)
    SettingsOverlay(vm)
    AppInfoOverlay(vm)
}

/** Requests POST_NOTIFICATIONS once on Android 13+ so file-op progress is actually visible. */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored: denial just means no progress notification */ }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
