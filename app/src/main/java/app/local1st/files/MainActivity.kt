package app.local1st.files

import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.core.prefs.LaunchTheme
import app.local1st.files.core.prefs.ThemeMode
import app.local1st.files.di.Graph
import app.local1st.files.ui.main.AppHost
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.theme.XFilesTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val incomingIntents = Channel<Intent>(Channel.BUFFERED)
    private val incomingIntentFlow = incomingIntents.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        // auto() with LaunchTheme so bar icons follow in-app night. Contrast is forced
        // off because auto() re-enables the API 29 nav-bar scrim inside enableEdgeToEdge.
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incomingIntents.trySend(intent)
        setContent {
            Root(incomingIntentFlow)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyEdgeToEdge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntents.trySend(intent)
    }

    private fun applyEdgeToEdge() {
        val transparent = Color.TRANSPARENT
        val detectDarkMode: (Resources) -> Boolean = { resources ->
            when (LaunchTheme.mode()) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = transparent,
                darkScrim = transparent,
                detectDarkMode = detectDarkMode,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = transparent,
                darkScrim = transparent,
                detectDarkMode = detectDarkMode,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }
}

@Composable
private fun Root(incomingIntents: Flow<Intent>) {
    val vm: MainViewModel = viewModel()
    val storedThemeMode by Graph.settings.themeMode.collectAsStateWithLifecycle(
        initialValue = LaunchTheme.mode(),
    )
    val storedDynamicColor by Graph.settings.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
    val sessionReady by vm.sessionReady.collectAsStateWithLifecycle()
    var applyStoredTheme by remember { mutableStateOf(false) }
    LaunchedEffect(sessionReady) {
        if (!sessionReady) return@LaunchedEffect
        withFrameNanos { }
        applyStoredTheme = true
    }
    val themeMode = if (applyStoredTheme) storedThemeMode else LaunchTheme.mode()
    val dynamicColor = if (applyStoredTheme) storedDynamicColor else true

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    XFilesTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        LaunchedEffect(vm, incomingIntents) {
            incomingIntents.collect(vm::openExternalIntent)
        }
        // AppHost keeps external viewers reachable without broad storage permission while making
        // every full-screen page a real destination instead of layering it over MainScreen.
        AppHost(vm)
    }
}
