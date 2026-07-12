package app.local1st.files.di

import app.local1st.files.core.fs.AppsFileSystem
import app.local1st.files.core.fs.ArchiveFileSystem
import app.local1st.files.core.fs.DefaultRootsRepository
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.RootFileSystem
import app.local1st.files.core.ops.DefaultOperationEngine
import app.local1st.files.core.search.DefaultSearchEngine

/** Wires concrete implementations into [Graph]. */
fun initGraph(graph: Graph) {
    graph.fsRegistry.register(LocalFileSystem())
    graph.fsRegistry.register(ArchiveFileSystem())
    graph.fsRegistry.register(AppsFileSystem(Graph.appContext))
    graph.fsRegistry.register(RootFileSystem())

    graph.roots = DefaultRootsRepository(Graph.appContext)
    graph.opEngine = DefaultOperationEngine(Graph.appScope, graph.fsRegistry, Graph.appContext.cacheDir)
    graph.searchEngine = DefaultSearchEngine(graph.fsRegistry)
}
