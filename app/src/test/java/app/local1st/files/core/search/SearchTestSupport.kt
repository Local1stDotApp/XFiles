package app.local1st.files.core.search

import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList

/** Every hit of a search below [root] that never pauses. */
internal suspend fun SearchEngine.hits(root: XEntry, query: String): List<SearchHit> =
    search(SearchScope.Subtree(root), query, toTheEnd = true)
        .filterIsInstance<SearchEvent.Hits>()
        .toList()
        .flatMap { it.hits }

/** Every hit of a search of all storage that never pauses. */
internal suspend fun SearchEngine.allStorageHits(query: String): List<SearchHit> =
    search(SearchScope.AllStorage, query, toTheEnd = true)
        .filterIsInstance<SearchEvent.Hits>()
        .toList()
        .flatMap { it.hits }

/** A filesystem whose every search listing calls [onList] first, e.g. to move a fake clock. */
internal class ObservedFileSystem(
    private val inner: XFileSystem,
    private val onList: (XEntry) -> Unit,
) : XFileSystem by inner {
    override fun listForSearch(dir: XEntry): List<XEntry> {
        onList(dir)
        return inner.listForSearch(dir)
    }
}
