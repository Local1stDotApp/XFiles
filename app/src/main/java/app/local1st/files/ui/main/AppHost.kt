package app.local1st.files.ui.main

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.local1st.files.di.Graph
import app.local1st.files.ui.appinfo.AppInfoScreen
import app.local1st.files.ui.dialogs.DestinationPickerScreen
import app.local1st.files.ui.dialogs.MainDialogs
import app.local1st.files.ui.dialogs.OpsHost
import app.local1st.files.ui.motion.AppScreenSurface
import app.local1st.files.ui.motion.AppScreenSwitcher
import app.local1st.files.ui.motion.DisplayBoundsMargin
import app.local1st.files.ui.motion.EnteringStartOffset
import app.local1st.files.ui.motion.frontPose
import app.local1st.files.ui.motion.rearPose
import app.local1st.files.ui.motion.rememberDisplayCornerRadius
import app.local1st.files.ui.motion.scrimAlpha
import app.local1st.files.ui.motion.towardEndSign
import app.local1st.files.ui.navigationBarsStable
import app.local1st.files.ui.search.SearchScreen
import app.local1st.files.ui.settings.SettingsScreen
import app.local1st.files.ui.viewer.ViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top-level screen host. At most two destinations are composed: the front page and, during
 * a transition, the page behind it. */
@Composable
fun AppHost(vm: MainViewModel) {
    val backStack = vm.screenBackStack
    val snackbarHostState = remember { SnackbarHostState() }
    val sessionReady by vm.sessionReady.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val displayCornerRadius = rememberDisplayCornerRadius()
    val scope = rememberCoroutineScope()
    val switcher = remember {
        AppScreenSwitcher(stack = backStack.toList(), scope = scope)
    }
    val holder = rememberSaveableStateHolder()
    val seenIds = remember { mutableSetOf<Long>() }
    val enteringOffsetPx = with(density) { EnteringStartOffset.toPx() }
    val marginPx = with(density) { DisplayBoundsMargin.toPx() }
    val towardEnd = towardEndSign(layoutDirection)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    LaunchedEffect(switcher) {
        snapshotFlow { backStack.toList() }.collect(switcher::onStackChanged)
    }

    LaunchedEffect(vm) {
        vm.snackbar.collect { snackbarHostState.showSnackbar(it) }
    }
    if (sessionReady) RequestNotificationPermission()
    if (sessionReady) LegacySafGrantHost(vm)
    if (sessionReady) SafLocationPickerHost(vm)

    val canGoBack = backStack.size > 1
    val gestureState = rememberNavigationEventState(
        currentInfo = NavigationEventInfo.None,
        backInfo = if (canGoBack) listOf(NavigationEventInfo.None) else emptyList(),
    )
    val backPreview = remember { object { var ignore = false } }
    LaunchedEffect(gestureState, switcher) {
        snapshotFlow { gestureState.transitionState }.collect { transition ->
            if (transition is NavigationEventTransitionState.InProgress) {
                if (backPreview.ignore) return@collect
                val current = backStack.lastOrNull() ?: return@collect
                val previous = backStack.getOrNull(backStack.lastIndex - 1) ?: return@collect
                switcher.onPreview(transition.latestEvent, current, previous)
            } else {
                backPreview.ignore = false
            }
        }
    }
    NavigationBackHandler(
        state = gestureState,
        isBackEnabled = canGoBack,
        onBackCancelled = {
            backPreview.ignore = false
            switcher.cancelPreview()
        },
        onBackCompleted = {
            backPreview.ignore = true
            switcher.completeBack { vm.navigateBack() }
        },
    )

    val front = switcher.front
    val rear = switcher.rear
    val renderedIds = listOfNotNull(front.id, rear?.id)
    SideEffect {
        seenIds += renderedIds
        val live = backStack.map { it.id }.toSet() + renderedIds
        seenIds.filter { it !in live }.forEach { id ->
            holder.removeState(id)
            seenIds.remove(id)
        }
    }

    val container = LocalWindowInfo.current.containerSize
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    var measuredHeight by remember { mutableFloatStateOf(0f) }
    val widthPx = measuredWidth.takeIf { it > 1f } ?: container.width.toFloat().coerceAtLeast(1f)
    val heightPx = measuredHeight.takeIf { it > 1f } ?: container.height.toFloat().coerceAtLeast(1f)
    val rearPose = switcher.rearPose(heightPx, marginPx, enteringOffsetPx, towardEnd)
    val frontPose = switcher.frontPose(widthPx, heightPx, marginPx, enteringOffsetPx, towardEnd)
    val scrim = switcher.scrimAlpha(isDark)

    val layers = buildList {
        if (rear != null) add(rear to rearPose)
        add(front to frontPose)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                measuredWidth = it.width.toFloat()
                measuredHeight = it.height.toFloat()
            }
            .background(MaterialTheme.colorScheme.background),
    ) {
        layers.forEachIndexed { index, (entry, pose) ->
            key(entry.id) {
                holder.SaveableStateProvider(entry.id) {
                    AppScreenSurface(
                        pose = pose,
                        displayCornerRadius = displayCornerRadius,
                    ) {
                        AppScreenBody(vm, entry)
                    }
                }
            }
            if (index == 0 && rear != null && scrim > 0.01f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsStable),
        )
    }
}

@Composable
private fun AppScreenBody(vm: MainViewModel, entry: AppScreenEntry) {
    when (val screen = entry.screen) {
        AppScreen.Browser -> Box(Modifier.fillMaxSize()) {
            PermissionGate(
                onGranted = vm::onStorageAccessGranted,
            ) {
                MainScreen(vm)
            }
            OpsHost()
            MainDialogs(vm)
        }

        is AppScreen.Search -> SearchScreen(
            vm = vm,
            root = screen.root,
            onBack = { vm.navigateBack(entry.id) },
        )

        AppScreen.Settings -> SettingsScreen(
            onBack = { vm.navigateBack(entry.id) },
        )

        is AppScreen.AppInfo -> AppInfoScreen(
            packageName = screen.packageName,
            onBack = { vm.navigateBack(entry.id) },
        )

        is AppScreen.Viewer -> ViewerScreen(
            vm = vm,
            request = screen.request,
            onBack = { vm.navigateBack(entry.id) },
        )

        is AppScreen.DestinationPicker -> DestinationPickerScreen(
            vm = vm,
            transfer = screen.transfer,
            onBack = { vm.navigateBack(entry.id) },
        )
    }
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

/** Opens the system folder picker for Add location on every API level. */
@Composable
private fun SafLocationPickerHost(vm: MainViewModel) {
    val nonce by Graph.locationActions.pickerNonce.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val uri = if (result.resultCode == Activity.RESULT_OK) data?.data else null
        vm.completeAddLocation(uri, data?.flags ?: 0)
    }
    LaunchedEffect(nonce) {
        if (!Graph.locationActions.takePickerLaunch(nonce)) return@LaunchedEffect
        runCatching { launcher.launch(Graph.locationActions.pickerIntent()) }
            .onFailure { vm.completeAddLocation(null, 0) }
    }
}

/** Completes one pending API 26-29 secondary-volume write, then lets it retry in place. */
@Composable
private fun LegacySafGrantHost(vm: MainViewModel) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
    val saf = Graph.legacySaf ?: return
    val request by saf.pendingGrant.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val uri = if (result.resultCode == Activity.RESULT_OK) data?.data else null
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                saf.completePendingGrant(uri, data?.flags ?: 0)
            }
            if (error != null) vm.snackbar.tryEmit(error)
        }
    }

    LaunchedEffect(request?.requestId) {
        val pending = request ?: return@LaunchedEffect
        vm.snackbar.tryEmit("Grant access to ${pending.volume.label} to finish the write")
        runCatching { launcher.launch(saf.pickerIntent(pending)) }
            .onFailure { error ->
                val message = withContext(Dispatchers.IO) {
                    saf.completePendingGrant(null, 0)
                }
                vm.snackbar.tryEmit(message ?: error.message ?: "Cannot open storage picker")
            }
    }
}
