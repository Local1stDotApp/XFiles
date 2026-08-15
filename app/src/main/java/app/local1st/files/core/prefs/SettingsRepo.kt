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
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
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

/**
 * Minimal information needed to list a restored container without first listing its parent.
 * These are routing hints only: startup still validates every entry through fresh parent listings.
 */
data class SessionDirectory(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val kind: EntryKind,
    val localPath: String?,
) {
    fun toEntry(): XEntry = XEntry(
        id = id,
        name = name,
        isDir = isDir,
        kind = kind,
        localPath = localPath,
    )

    companion object {
        fun fromEntry(entry: XEntry): SessionDirectory = SessionDirectory(
            id = entry.id,
            name = entry.name,
            isDir = entry.isDir,
            kind = entry.kind,
            localPath = entry.localPath,
        )
    }
}

/** One presentation-only row retained so the next process can draw before fresh filesystem IO. */
data class SessionRenderNode(
    val entry: XEntry,
    val key: String,
    val depth: Int,
    val expanded: Boolean,
    val guides: List<Boolean>,
    val isLastChild: Boolean,
)

/** A bounded window around the restored row; never trusted as current filesystem state. */
data class SessionRenderSnapshot(
    val nodes: List<SessionRenderNode>,
    val initialIndex: Int,
)

/** One pane's browsing position as persisted between launches. */
data class SessionPane(
    val expandedIds: Set<String>,
    val focusedId: String?,
    val directories: List<SessionDirectory> = emptyList(),
    val renderSnapshot: SessionRenderSnapshot? = null,
)

/** Where the user left off: both panes' positions plus which pane was active. */
data class SessionState(val panes: List<SessionPane>, val activePane: Int)

/**
 * A pinned top-level shortcut. [isDir] is stored so an unavailable favorite
 * (target deleted, volume unmounted) still renders as the right thing — a stat
 * failure can't tell a missing folder from a missing file.
 */
data class Favorite(val id: String, val isDir: Boolean)

/** Default for the Root-access switch: the home-screen row is visible; writes stay read-only. */
const val DEFAULT_ROOT_ENABLED = true

private const val MAX_SESSION_DIRECTORIES = 128
internal const val MAX_SESSION_RENDER_NODES = 32
private const val MAX_SESSION_RENDER_DEPTH = 64

private fun encodeSessionDirectories(directories: List<SessionDirectory>): String {
    val array = JSONArray()
    directories.distinctBy { it.id }.take(MAX_SESSION_DIRECTORIES).forEach { directory ->
        array.put(
            JSONObject()
                .put("id", directory.id)
                .put("name", directory.name)
                .put("dir", directory.isDir)
                .put("kind", directory.kind.name)
                .put("local", directory.localPath ?: JSONObject.NULL),
        )
    }
    return array.toString()
}

private fun decodeSessionDirectories(json: String?): List<SessionDirectory> {
    if (json.isNullOrEmpty()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            val seen = HashSet<String>()
            for (index in 0 until minOf(array.length(), MAX_SESSION_DIRECTORIES)) {
                val value = array.optJSONObject(index) ?: continue
                val id = value.optString("id")
                if (!id.contains("://") || !seen.add(id)) continue
                val kind = runCatching { EntryKind.valueOf(value.optString("kind")) }
                    .getOrNull() ?: continue
                val localPath = value.opt("local")
                    ?.takeUnless { it == JSONObject.NULL } as? String
                add(
                    SessionDirectory(
                        id = id,
                        name = value.optString("name"),
                        isDir = value.optBoolean("dir", kind == EntryKind.DIR),
                        kind = kind,
                        localPath = localPath,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun JSONObject.nullableString(key: String): String? =
    opt(key)?.takeUnless { it == JSONObject.NULL } as? String

private fun encodeSessionRender(snapshot: SessionRenderSnapshot): String {
    val nodes = JSONArray()
    snapshot.nodes.take(MAX_SESSION_RENDER_NODES).forEach { node ->
        val entry = node.entry
        nodes.put(
            JSONObject()
                .put("i", entry.id)
                .put("n", entry.name)
                .put("d", entry.isDir)
                .put("s", entry.size)
                .put("t", entry.mtime)
                .put("m", entry.mime ?: JSONObject.NULL)
                .put("h", entry.hidden)
                .put("r", entry.canRead)
                .put("w", entry.canWrite)
                .put("k", entry.kind.name)
                .put("c", entry.childCountHint)
                .put("b", entry.badge ?: JSONObject.NULL)
                .put("l", entry.localPath ?: JSONObject.NULL)
                .put("p", entry.progress.toDouble())
                .put("f", entry.pinned)
                .put("q", node.key)
                .put("z", node.depth)
                .put("x", node.expanded)
                .put("g", node.guides.joinToString("") { if (it) "1" else "0" })
                .put("e", node.isLastChild),
        )
    }
    return JSONObject()
        .put("at", snapshot.initialIndex)
        .put("nodes", nodes)
        .toString()
}

private fun decodeSessionRender(json: String?): SessionRenderSnapshot? {
    if (json.isNullOrEmpty()) return null
    return runCatching {
        val root = JSONObject(json)
        val array = root.optJSONArray("nodes") ?: return@runCatching null
        val nodes = buildList {
            for (index in 0 until minOf(array.length(), MAX_SESSION_RENDER_NODES)) {
                val value = array.optJSONObject(index) ?: continue
                val id = value.optString("i")
                if (!id.contains("://")) continue
                val kind = runCatching { EntryKind.valueOf(value.optString("k")) }
                    .getOrNull() ?: continue
                val isDir = value.optBoolean("d", kind == EntryKind.DIR)
                val entry = XEntry(
                    id = id,
                    name = value.optString("n"),
                    isDir = isDir,
                    size = value.optLong("s", -1L),
                    mtime = value.optLong("t", 0L),
                    mime = value.nullableString("m"),
                    hidden = value.optBoolean("h", false),
                    canRead = value.optBoolean("r", true),
                    canWrite = value.optBoolean("w", true),
                    kind = kind,
                    childCountHint = value.optInt("c", -1),
                    badge = value.nullableString("b"),
                    localPath = value.nullableString("l"),
                    progress = value.optDouble("p", -1.0).toFloat(),
                    pinned = value.optBoolean("f", false),
                )
                val key = value.optString("q").takeIf(String::isNotEmpty) ?: id
                add(
                    SessionRenderNode(
                        entry = entry,
                        key = key,
                        depth = value.optInt("z", 0).coerceIn(0, MAX_SESSION_RENDER_DEPTH),
                        expanded = value.optBoolean("x", false),
                        guides = value.optString("g")
                            .take(MAX_SESSION_RENDER_DEPTH)
                            .map { it == '1' },
                        isLastChild = value.optBoolean("e", false),
                    ),
                )
            }
        }
        if (nodes.isEmpty()) null else SessionRenderSnapshot(
            nodes = nodes,
            initialIndex = root.optInt("at", 0).coerceIn(nodes.indices),
        )
    }.getOrNull()
}

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
    private val keyCollapseSiblingFolders = booleanPreferencesKey("collapse_sibling_folders")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyDynamicColor = booleanPreferencesKey("dynamic_color")
    private val keyTextWrap = booleanPreferencesKey("text_wrap")
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
    private val keySessionDirectories = listOf(
        stringPreferencesKey("session_directories_0"),
        stringPreferencesKey("session_directories_1"),
    )
    private val keySessionRender = listOf(
        stringPreferencesKey("session_render_0"),
        stringPreferencesKey("session_render_1"),
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
    /** Accordion-style tree navigation is the default; an explicit saved choice still wins. */
    val collapseSiblingFolders: Flow<Boolean> =
        setting { it[keyCollapseSiblingFolders] ?: true }
    val themeMode: Flow<ThemeMode> =
        setting { runCatching { ThemeMode.valueOf(it[keyThemeMode] ?: "") }.getOrDefault(ThemeMode.SYSTEM) }
    val dynamicColor: Flow<Boolean> = setting { it[keyDynamicColor] ?: true }

    /**
     * Whether the text viewer breaks long lines to the window. Off by default: wrapping is what
     * destroys the shape of indented text, and XML, JSON and source are most of what gets opened.
     */
    val textWrap: Flow<Boolean> = setting { it[keyTextWrap] ?: false }

    /** Root row is on the home screen by default; Read-only still blocks privileged writes. */
    val rootEnabled: Flow<Boolean> = setting { it[keyRootEnabled] ?: DEFAULT_ROOT_ENABLED }

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
                    directories = decodeSessionDirectories(prefs[keySessionDirectories[i]]),
                    renderSnapshot = decodeSessionRender(prefs[keySessionRender[i]]),
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
            if (pane.directories.isEmpty()) prefs.remove(keySessionDirectories[i])
            else prefs[keySessionDirectories[i]] = encodeSessionDirectories(pane.directories)
            val renderSnapshot = pane.renderSnapshot
            if (renderSnapshot == null || renderSnapshot.nodes.isEmpty()) {
                prefs.remove(keySessionRender[i])
            } else {
                prefs[keySessionRender[i]] = encodeSessionRender(renderSnapshot)
            }
        }
        prefs[keySessionActivePane] = state.activePane
    }

    suspend fun setShowHidden(value: Boolean) = context.dataStore.edit { it[keyShowHidden] = value }
    suspend fun setSortBy(value: SortBy) = context.dataStore.edit { it[keySortBy] = value.name }
    suspend fun setSortDescending(value: Boolean) = context.dataStore.edit { it[keySortDescending] = value }
    suspend fun setDirsFirst(value: Boolean) = context.dataStore.edit { it[keyDirsFirst] = value }
    suspend fun setCollapseSiblingFolders(value: Boolean) = context.dataStore.edit {
        it[keyCollapseSiblingFolders] = value
    }
    suspend fun setThemeMode(value: ThemeMode) = context.dataStore.edit { it[keyThemeMode] = value.name }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[keyDynamicColor] = value }
    suspend fun setTextWrap(value: Boolean) = context.dataStore.edit { it[keyTextWrap] = value }
    suspend fun setRootEnabled(value: Boolean) = context.dataStore.edit { it[keyRootEnabled] = value }
    suspend fun setRootReadOnly(value: Boolean) = context.dataStore.edit { it[keyRootReadOnly] = value }
    suspend fun setPrivilegedTransport(value: TransportPref) = context.dataStore.edit {
        it[keyPrivilegedTransport] = value.storedValue
    }
}
