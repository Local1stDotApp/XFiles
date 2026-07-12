package com.xfiles.di

import com.xfiles.core.fs.AppsFileSystem
import com.xfiles.core.fs.ArchiveFileSystem
import com.xfiles.core.fs.DefaultRootsRepository
import com.xfiles.core.fs.LocalFileSystem
import com.xfiles.core.fs.RootFileSystem
import com.xfiles.core.ops.DefaultOperationEngine
import com.xfiles.core.search.DefaultSearchEngine

/** Wires concrete implementations into [Graph]. */
fun initGraph(graph: Graph) {
    graph.fsRegistry.register(LocalFileSystem())
    graph.fsRegistry.register(ArchiveFileSystem())
    graph.fsRegistry.register(AppsFileSystem(Graph.appContext))
    graph.fsRegistry.register(RootFileSystem())

    graph.roots = DefaultRootsRepository(Graph.appContext)
    graph.opEngine = DefaultOperationEngine(Graph.appScope, graph.fsRegistry)
    graph.searchEngine = DefaultSearchEngine(graph.fsRegistry)
}
