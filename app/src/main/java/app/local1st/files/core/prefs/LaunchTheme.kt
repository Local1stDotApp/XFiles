package app.local1st.files.core.prefs

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * In-app theme is in DataStore, which is too late for the first window frame.
 * [syncBlocking] reads it once at process start into memory — not SharedPreferences,
 * and without overlaying [Configuration.uiMode], which would make "System" lie.
 */
object LaunchTheme {
    @Volatile
    private var cached: ThemeMode = ThemeMode.SYSTEM

    fun mode(): ThemeMode = cached

    fun isDark(context: Context): Boolean = when (cached) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            night == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun persist(context: Context, mode: ThemeMode) {
        cached = mode
        applyApplicationNightMode(context, mode)
    }

    fun syncBlocking(settings: SettingsRepo) {
        cached = runBlocking { settings.themeMode.first() }
    }

    private fun applyApplicationNightMode(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val night = when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(night)
    }
}
