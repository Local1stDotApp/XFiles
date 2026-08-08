package app.local1st.files.di

import android.content.Context
import android.os.Build
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.LegacySafAccess
import app.local1st.files.core.fs.RootsRepository
import app.local1st.files.core.ops.OperationEngine
import app.local1st.files.core.prefs.Favorite
import app.local1st.files.core.prefs.SessionState
import app.local1st.files.core.prefs.SettingsRepo
import app.local1st.files.core.search.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicBoolean

data class StartupSessionLoad(
    val deferred: Deferred<SessionState>,
    val snapshot: SessionState?,
)

/**
 * Manual composition root. Initialized once from XFilesApp.onCreate via [init];
 * wiring of concrete implementations lives in GraphInit.kt.
 */
object Graph {
    lateinit var appContext: Context
        private set

    /** App-lifetime scope for operations that outlive any screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepo by lazy { SettingsRepo(appContext) }
    /** Starts with Application.onCreate so the render snapshot can beat Compose's first draw. */
    private lateinit var startupSession: Deferred<SessionState>
    @Volatile
    private var startupSessionSnapshot: SessionState? = null
    private val startupSessionClaimed = AtomicBoolean(false)
    /** Null on API 30+: those releases must never enter or initialize the SAF write path. */
    val legacySaf: LegacySafAccess? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) LegacySafAccess(appContext, settings)
        else null
    }
    val fsRegistry: FsRegistry = FsRegistry()

    /**
     * Favorites cached for synchronous reads while building pane roots.
     * Null until the first DataStore read lands (callers that must not miss
     * favorites await the first non-null value).
     */
    val favorites: StateFlow<List<Favorite>?> by lazy {
        settings.favorites.stateIn<List<Favorite>?>(appScope, SharingStarted.Eagerly, null)
    }

    lateinit var roots: RootsRepository
    lateinit var opEngine: OperationEngine
    lateinit var searchEngine: SearchEngine

    /** One process-start ViewModel may use the preload; later instances must read current state. */
    fun claimStartupSession(): StartupSessionLoad? {
        if (!startupSessionClaimed.compareAndSet(false, true)) return null
        return StartupSessionLoad(startupSession, startupSessionSnapshot)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        startupSession = appScope.async(Dispatchers.IO) {
            settings.loadSession().also { startupSessionSnapshot = it }
        }
        initGraph(this)
    }
}
