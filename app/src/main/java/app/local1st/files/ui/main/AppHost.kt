package app.local1st.files.ui.main

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.local1st.files.di.Graph
import app.local1st.files.ui.appinfo.AppInfoScreen
import app.local1st.files.ui.dialogs.DestinationPickerScreen
import app.local1st.files.ui.dialogs.MainDialogs
import app.local1st.files.ui.dialogs.OpsHost
import app.local1st.files.ui.search.SearchScreen
import app.local1st.files.ui.settings.SettingsScreen
import app.local1st.files.ui.viewer.ViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top-level screen host. Exactly one full-screen destination is composed at a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppHost(vm: MainViewModel) {
    val backStack = vm.screenBackStack
    val snackbarHostState = remember { SnackbarHostState() }
    val sessionReady by vm.sessionReady.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.snackbar.collect { snackbarHostState.showSnackbar(it) }
    }
    if (sessionReady) RequestNotificationPermission()
    if (sessionReady) LegacySafGrantHost(vm)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { vm.navigateBack() },
            entryProvider = { destination ->
                NavEntry(destination) {
                    val screenEntry = destination
                    val screen = screenEntry.screen
                    when (screen) {
                        AppScreen.Browser -> Box(Modifier.fillMaxSize()) {
                            PermissionGate(
                                onGranted = vm::onStorageAccessGranted,
                            ) {
                                MainScreen(vm)
                            }
                            // Browser dialogs and file-operation cards remain transient UI. They
                            // are not composed while another full-screen destination is active.
                            OpsHost()
                            MainDialogs(vm)
                        }

                        is AppScreen.Search -> SearchScreen(
                            vm = vm,
                            root = screen.root,
                            onBack = { vm.navigateBack(screenEntry.id) },
                        )

                        AppScreen.Settings -> SettingsScreen(
                            onBack = { vm.navigateBack(screenEntry.id) },
                        )

                        is AppScreen.AppInfo -> AppInfoScreen(
                            packageName = screen.packageName,
                            onBack = { vm.navigateBack(screenEntry.id) },
                        )

                        is AppScreen.Viewer -> ViewerScreen(
                            vm = vm,
                            request = screen.request,
                            onBack = { vm.navigateBack(screenEntry.id) },
                        )

                        is AppScreen.DestinationPicker -> DestinationPickerScreen(
                            vm = vm,
                            transfer = screen.transfer,
                            onBack = { vm.navigateBack(screenEntry.id) },
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
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
