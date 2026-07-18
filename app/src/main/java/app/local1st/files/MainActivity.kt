package app.local1st.files

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.core.prefs.ThemeMode
import app.local1st.files.di.Graph
import app.local1st.files.ui.main.MainScreen
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.main.PermissionGate
import app.local1st.files.ui.theme.XFilesTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            Root()
        }
    }
}

@Composable
private fun Root() {
    val themeMode by Graph.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by Graph.settings.dynamicColor.collectAsStateWithLifecycle(initialValue = true)

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    XFilesTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        val vm: MainViewModel = viewModel()
        PermissionGate(
            onGranted = { vm.onStorageAccessGranted() },
        ) {
            MainScreen(vm)
        }
    }
}
