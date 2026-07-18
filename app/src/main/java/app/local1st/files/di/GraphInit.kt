package app.local1st.files.di

import app.local1st.files.core.fs.AppsFileSystem
import app.local1st.files.core.fs.ArchiveFileSystem
import app.local1st.files.core.fs.DefaultRootsRepository
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.RootAccess
import app.local1st.files.core.fs.RootFileSystem
import app.local1st.files.core.ops.DefaultOperationEngine
import app.local1st.files.core.search.DefaultSearchEngine
import kotlinx.coroutines.launch

/** Wires concrete implementations into [Graph]. */
fun initGraph(graph: Graph) {
    graph.fsRegistry.register(LocalFileSystem())
    graph.fsRegistry.register(ArchiveFileSystem())
    graph.fsRegistry.register(AppsFileSystem(Graph.appContext))
    graph.fsRegistry.register(RootFileSystem())

    graph.roots = DefaultRootsRepository(
        Graph.appContext,
        favorites = { Graph.favorites.value.orEmpty() },
        statById = { id -> Graph.fsRegistry.forId(id).stat(id) },
    )
    graph.opEngine = DefaultOperationEngine(Graph.appScope, graph.fsRegistry, Graph.appContext.cacheDir)
    graph.searchEngine = DefaultSearchEngine(graph.fsRegistry)

    // Mirror the root-access settings into the process-wide gate consulted by the fs layer.
    // App-lifetime so file operations honor read-only mode even without a UI in the foreground.
    Graph.appScope.launch { Graph.settings.rootEnabled.collect { RootAccess.enabled = it } }
    Graph.appScope.launch { Graph.settings.rootReadOnly.collect { RootAccess.readOnly = it } }
}
