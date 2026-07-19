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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Matches enableEdgeToEdge()'s legacy navigation-bar scrims. API 26-28 cannot rely on
// platform contrast enforcement, so the background and icon appearance must change together.
private val LegacyLightNavigationBarScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val LegacyDarkNavigationBarScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

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

    // enableEdgeToEdge() only follows the *system* dark mode; the in-app theme
    // preference must drive the bar icon appearance too, and reactively.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull() ?: return@SideEffect
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                activity.window.navigationBarColor = if (darkTheme) {
                    LegacyDarkNavigationBarScrim
                } else {
                    LegacyLightNavigationBarScrim
                }
            }
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
