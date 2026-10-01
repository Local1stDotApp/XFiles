package app.local1st.files.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Matches enableEdgeToEdge()'s legacy navigation-bar scrims. API 26-28 cannot rely on
// platform contrast enforcement, so the background and icon appearance must change together.
private val LegacyLightNavigationBarScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val LegacyDarkNavigationBarScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

/** How many composed screens currently need [DarkSystemBars]. */
private val LocalDarkSystemBarRequests = staticCompositionLocalOf<MutableIntState> {
    mutableIntStateOf(0)
}

/**
 * Gives the system bars light icons while the caller is composed, whatever the theme. For screens
 * that stay black behind the bars, such as the image and video viewers, where a light theme's dark
 * icons would vanish. The theme stays the only writer of the bars' look, so leaving the screen
 * brings back the theme's look instead of whatever was there before it opened.
 */
@Composable
fun DarkSystemBars() {
    val requests = LocalDarkSystemBarRequests.current
    DisposableEffect(requests) {
        requests.intValue++
        onDispose { requests.intValue-- }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun XFilesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }

    val darkBarRequests = remember { mutableIntStateOf(0) }
    val darkBars = darkTheme || darkBarRequests.intValue > 0

    // enableEdgeToEdge() only follows the *system* dark mode; the in-app theme
    // preference must drive the bar icon appearance too, and reactively.
    // Keyed on the configuration as well: MainActivity re-runs enableEdgeToEdge() on every
    // configuration change, which puts the theme's look back under a dark screen.
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    if (!view.isInEditMode) {
        DisposableEffect(view, darkBars, configuration) {
            val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull() ?: return@DisposableEffect onDispose { }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                activity.window.navigationBarColor = if (darkBars) {
                    LegacyDarkNavigationBarScrim
                } else {
                    LegacyLightNavigationBarScrim
                }
            } else {
                // Android 10's contrast scrim is an opaque light bar; keep the
                // transparent bars so Compose's background fills the insets.
                activity.window.isStatusBarContrastEnforced = false
                activity.window.isNavigationBarContrastEnforced = false
            }
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkBars
            controller.isAppearanceLightNavigationBars = !darkBars
            onDispose { }
        }
    }

    CompositionLocalProvider(LocalDarkSystemBarRequests provides darkBarRequests) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
