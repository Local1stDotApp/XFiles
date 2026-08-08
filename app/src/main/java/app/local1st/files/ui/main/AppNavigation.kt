package app.local1st.files.ui.main

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.local1st.files.core.fs.XEntry
import app.local1st.files.ui.viewer.ViewerRequest

/** A full-screen destination. Dialogs and operation progress are not navigation destinations. */
sealed interface AppScreen {
    data object Browser : AppScreen
    data class Search(val root: XEntry) : AppScreen
    data object Settings : AppScreen
    data class AppInfo(val packageName: String) : AppScreen
    data class Viewer(val request: ViewerRequest) : AppScreen
    data class DestinationPicker(val transfer: PendingTransfer) : AppScreen
}

/** Stable identity lets Compose retain saveable UI state for screens below the current one. */
class AppScreenEntry internal constructor(
    val id: Long,
    val screen: AppScreen,
) {
    // Viewer and picker payloads may contain long entry lists. Navigation identity is the cheap,
    // stable id rather than recursively hashing those payloads whenever NavDisplay resolves state.
    override fun equals(other: Any?): Boolean = other is AppScreenEntry && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "AppScreenEntry(id=$id)"
}

/**
 * Small in-memory navigation stack tailored to this single-activity app.
 *
 * Payloads such as [XEntry] and viewer playlists stay strongly typed instead of being serialized
 * into route strings. The browser is the root and cannot be popped.
 */
internal class AppNavigationState {
    private var nextId = 1L
    val backStack: SnapshotStateList<AppScreenEntry> = mutableStateListOf(
        AppScreenEntry(id = 0L, screen = AppScreen.Browser),
    )

    fun navigate(screen: AppScreen, replaceTop: Boolean = false): AppScreenEntry {
        val current = backStack.last()
        if (current.screen === screen) return current

        val entry = AppScreenEntry(id = nextId++, screen = screen)
        if (replaceTop && backStack.size > 1) {
            backStack[backStack.lastIndex] = entry
        } else {
            backStack += entry
        }
        return entry
    }

    /** Pops only the screen that issued the callback, so a stale callback cannot pop a new page. */
    fun navigateBack(expectedEntryId: Long? = null): Boolean {
        if (backStack.size <= 1) return false
        if (expectedEntryId != null && backStack.last().id != expectedEntryId) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }
}
