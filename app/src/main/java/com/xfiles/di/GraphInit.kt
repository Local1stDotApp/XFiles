package com.xfiles.di

/**
 * Wires concrete implementations into [Graph].
 * Filled in as feature implementations land; keep all `Graph.x = ...` assignments here.
 */
fun initGraph(graph: Graph) {
    // Implementations are registered during integration:
    //  - graph.fsRegistry.register(LocalFileSystem(...)) etc.
    //  - graph.roots = DefaultRootsRepository(...)
    //  - graph.opEngine = DefaultOperationEngine(...)
    //  - graph.searchEngine = DefaultSearchEngine(...)
}
