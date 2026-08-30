package app.local1st.files.core.prefs

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * First window chrome and the first tree frame follow [Configuration] night mode.
 * [cacheFromStore] is filled off the main thread from the same DataStore read as
 * the session snapshot; Compose applies that in-app theme only after the restored
 * tree has committed a frame. [persist] is the user-change path so API 31+ already
 * has the right night mode on the next process.
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

    /** In-memory only — do not call [UiModeManager.setApplicationNightMode] here. */
    fun cacheFromStore(mode: ThemeMode) {
        cached = mode
    }

    fun persist(context: Context, mode: ThemeMode) {
        cached = mode
        applyApplicationNightMode(context, mode)
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
