package app.local1st.files.di

import android.content.Context
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.RootsRepository
import app.local1st.files.core.ops.OperationEngine
import app.local1st.files.core.prefs.SettingsRepo
import app.local1st.files.core.search.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val fsRegistry: FsRegistry = FsRegistry()

    lateinit var roots: RootsRepository
    lateinit var opEngine: OperationEngine
    lateinit var searchEngine: SearchEngine

    fun init(context: Context) {
        appContext = context.applicationContext
        initGraph(this)
    }
}
