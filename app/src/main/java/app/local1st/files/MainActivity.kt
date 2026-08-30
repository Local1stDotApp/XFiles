package app.local1st.files

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.R
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
        // SystemBarStyle.auto() turns on navigation-bar contrast enforcement on API 29,
        // and Android 10 then paints an opaque light scrim over the bar. dark()/light()
        // keep the bar transparent so the M3 window background shows through.
        val dark = LaunchTheme.isDark(this)
        window.setBackgroundDrawableResource(
            if (dark) R.color.app_background_dark else R.color.app_background_light,
        )
        val transparent = Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incomingIntents.trySend(intent)
        setContent {
            Root(incomingIntentFlow)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntents.trySend(intent)
    }
}

@Composable
private fun Root(incomingIntents: Flow<Intent>) {
    val themeMode by Graph.settings.themeMode.collectAsStateWithLifecycle(
        initialValue = LaunchTheme.mode(),
    )
    val dynamicColor by Graph.settings.dynamicColor.collectAsStateWithLifecycle(initialValue = true)

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    XFilesTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        val vm: MainViewModel = viewModel()
        LaunchedEffect(vm, incomingIntents) {
            incomingIntents.collect(vm::openExternalIntent)
        }
        // AppHost keeps external viewers reachable without broad storage permission while making
        // every full-screen page a real destination instead of layering it over MainScreen.
        AppHost(vm)
    }
}
