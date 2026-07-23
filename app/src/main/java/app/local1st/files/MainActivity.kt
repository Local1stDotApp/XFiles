package app.local1st.files

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.core.prefs.ThemeMode
import app.local1st.files.di.Graph
import app.local1st.files.ui.main.MainScreen
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.main.PermissionGate
import app.local1st.files.ui.theme.XFilesTheme
import app.local1st.files.ui.viewer.ViewerHost
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val incomingIntents = Channel<Intent>(Channel.BUFFERED)
    private val incomingIntentFlow = incomingIntents.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
    val themeMode by Graph.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
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
        Box {
            PermissionGate(
                onGranted = { vm.onStorageAccessGranted() },
            ) {
                MainScreen(vm)
            }
            // External image/video URIs do not require broad storage permission.
            ViewerHost(vm)
        }
    }
}
