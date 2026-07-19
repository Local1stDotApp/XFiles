package app.local1st.files.core.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.local1st.files.core.fs.priv.TransportPref
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

enum class SortBy { NAME, SIZE, DATE, TYPE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** One pane's browsing position as persisted between launches. */
data class SessionPane(val expandedIds: Set<String>, val focusedId: String?)

/** Where the user left off: both panes' positions plus which pane was active. */
data class SessionState(val panes: List<SessionPane>, val activePane: Int)

/**
 * A pinned top-level shortcut. [isDir] is stored so an unavailable favorite
 * (target deleted, volume unmounted) still renders as the right thing — a stat
 * failure can't tell a missing folder from a missing file.
 */
data class Favorite(val id: String, val isDir: Boolean)

// A corrupted file would otherwise throw from every edit() (the read-path catch below
// can't help writes); recover with defaults instead of crash-looping the saver.
private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepo(private val context: Context) {

    private val keyShowHidden = booleanPreferencesKey("show_hidden")
    private val keySortBy = stringPreferencesKey("sort_by")
    private val keySortDescending = booleanPreferencesKey("sort_descending")
    private val keyDirsFirst = booleanPreferencesKey("dirs_first")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyDynamicColor = booleanPreferencesKey("dynamic_color")
    private val keyRootEnabled = booleanPreferencesKey("root_enabled")
    private val keyRootReadOnly = booleanPreferencesKey("root_read_only")
    private val keyPrivilegedTransport = stringPreferencesKey("privileged_transport")
    private val keySafVolumeTrees = stringPreferencesKey("saf_volume_trees")
    // JSON array, not a string set: favorites keep their user-defined order.
    private val keyFavorites = stringPreferencesKey("favorites")
    private val keySessionActivePane = intPreferencesKey("session_active_pane")
    private val keySessionExpanded = listOf(
        stringSetPreferencesKey("session_expanded_0"),
        stringSetPreferencesKey("session_expanded_1"),
    )
    private val keySessionFocused = listOf(
        stringPreferencesKey("session_focused_0"),
        stringPreferencesKey("session_focused_1"),
    )

    // A corrupted preferences file surfaces as an IOException on every read; recover with
    // defaults instead of crash-looping the app at startup. DataStore re-emits a snapshot
    // on every edit of ANY key, so each derived flow dedups — otherwise the session
    // auto-save would re-fire every settings collector after each navigation action.
    private val data = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private fun <T> setting(read: (Preferences) -> T): Flow<T> =
        data.map(read).distinctUntilChanged()

    val showHidden: Flow<Boolean> = setting { it[keyShowHidden] ?: false }
    val sortBy: Flow<SortBy> =
        setting { runCatching { SortBy.valueOf(it[keySortBy] ?: "") }.getOrDefault(SortBy.NAME) }
    val sortDescending: Flow<Boolean> = setting { it[keySortDescending] ?: false }
    val dirsFirst: Flow<Boolean> = setting { it[keyDirsFirst] ?: true }
    val themeMode: Flow<ThemeMode> =
        setting { runCatching { ThemeMode.valueOf(it[keyThemeMode] ?: "") }.getOrDefault(ThemeMode.SYSTEM) }
    val dynamicColor: Flow<Boolean> = setting { it[keyDynamicColor] ?: true }

    /** Root browsing is off until the user opts in (dangerous, so default false). */
    val rootEnabled: Flow<Boolean> = setting { it[keyRootEnabled] ?: false }

    /** Read-only root mode is the safe default: block writes that need root. */
    val rootReadOnly: Flow<Boolean> = setting { it[keyRootReadOnly] ?: true }

    val privilegedTransport: Flow<TransportPref> = setting {
        TransportPref.fromStoredValue(it[keyPrivilegedTransport] ?: "auto")
    }

    /** Persisted SAF tree URI per secondary-volume id (API 26-29 only). */
    val safVolumeTrees: Flow<Map<String, String>> = setting { prefs ->
        val json = prefs[keySafVolumeTrees] ?: return@setting emptyMap()
        runCatching {
            val objectValue = JSONObject(json)
            buildMap {
                val keys = objectValue.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, objectValue.getString(key))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Entries pinned as top-level favorites, in display order. */
    val favorites: Flow<List<Favorite>> = setting { prefs ->
        val json = prefs[keyFavorites] ?: return@setting emptyList()
        runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Favorite(id = o.getString("id"), isDir = o.optBoolean("dir", true))
            }
        }.getOrDefault(emptyList())
    }

    suspend fun setFavorites(favorites: List<Favorite>) = context.dataStore.edit { prefs ->
        val arr = JSONArray()
        favorites.forEach { arr.put(JSONObject().put("id", it.id).put("dir", it.isDir)) }
        prefs[keyFavorites] = arr.toString()
    }

    suspend fun setSafVolumeTree(volumeId: String, treeUri: String?) =
        context.dataStore.edit { prefs ->
            val trees = runCatching {
                JSONObject(prefs[keySafVolumeTrees] ?: "{}")
            }.getOrElse { JSONObject() }
            if (treeUri == null) trees.remove(volumeId) else trees.put(volumeId, treeUri)
            if (trees.length() == 0) prefs.remove(keySafVolumeTrees)
            else prefs[keySafVolumeTrees] = trees.toString()
        }

    /** One-shot read of the persisted session (last browsing position). */
    suspend fun loadSession(): SessionState {
        val prefs = data.first()
        return SessionState(
            panes = List(2) { i ->
                SessionPane(
                    expandedIds = prefs[keySessionExpanded[i]] ?: emptySet(),
                    focusedId = prefs[keySessionFocused[i]],
                )
            },
            activePane = prefs[keySessionActivePane] ?: 0,
        )
    }

    suspend fun saveSession(state: SessionState) = context.dataStore.edit { prefs ->
        state.panes.take(2).forEachIndexed { i, pane ->
            prefs[keySessionExpanded[i]] = pane.expandedIds
            val focused = pane.focusedId
            if (focused != null) prefs[keySessionFocused[i]] = focused
            else prefs.remove(keySessionFocused[i])
        }
        prefs[keySessionActivePane] = state.activePane
    }

    suspend fun setShowHidden(value: Boolean) = context.dataStore.edit { it[keyShowHidden] = value }
    suspend fun setSortBy(value: SortBy) = context.dataStore.edit { it[keySortBy] = value.name }
    suspend fun setSortDescending(value: Boolean) = context.dataStore.edit { it[keySortDescending] = value }
    suspend fun setDirsFirst(value: Boolean) = context.dataStore.edit { it[keyDirsFirst] = value }
    suspend fun setThemeMode(value: ThemeMode) = context.dataStore.edit { it[keyThemeMode] = value.name }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[keyDynamicColor] = value }
    suspend fun setRootEnabled(value: Boolean) = context.dataStore.edit { it[keyRootEnabled] = value }
    suspend fun setRootReadOnly(value: Boolean) = context.dataStore.edit { it[keyRootReadOnly] = value }
    suspend fun setPrivilegedTransport(value: TransportPref) = context.dataStore.edit {
        it[keyPrivilegedTransport] = value.storedValue
    }
}
