package app.local1st.files.core.search

import app.local1st.files.core.fs.XEntry
import kotlinx.coroutines.flow.Flow

data class SearchHit(
    val entry: XEntry,
    /** Human-readable location of the parent container. */
    val parentId: String,
)

/** Recursive filename search below a root container. Implementation: DefaultSearchEngine. */
interface SearchEngine {
    /**
     * Emits hits as they are found. Cancellation of the collecting coroutine stops the walk.
     * [query] is matched case-insensitively as a substring; '*' and '?' wildcards supported.
     */
    fun search(root: XEntry, query: String): Flow<SearchHit>
}
