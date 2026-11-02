package app.local1st.files.core.search

import app.local1st.files.core.fs.XEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

data class SearchHit(
    val entry: XEntry,
    /** Human-readable location of the parent container. */
    val parentId: String,
)

/** Where a search looks. */
sealed interface SearchScope {
    /** Everything below [root], recursively. */
    data class Subtree(val root: XEntry) : SearchScope

    /**
     * Every mounted storage volume. Archives are not opened, and Android/data and
     * Android/obb are left out, so a phone-wide search stays cheap with or without an index.
     */
    data object AllStorage : SearchScope
}

/** The user's answer when a round of a walk is used up. */
enum class Resume {
    /** Search for one more round, then ask again. */
    NEXT_ROUND,

    /** Search until the walk ends, without asking again. */
    TO_THE_END,
}

enum class SearchOutcome {
    /** Everything in scope was looked at. */
    COMPLETE,

    /** More names matched than a search shows. The rest were not looked for. */
    TOO_MANY_HITS,
}

sealed interface SearchEvent {
    /** New matches, in batches. */
    data class Hits(val hits: List<SearchHit>) : SearchEvent

    /**
     * Entries looked at so far, plus each folder opened. Sent a few times a second at most.
     * A walk through empty folders still makes it grow.
     */
    data class Progress(val examined: Int) : SearchEvent

    /**
     * A round's time is up. The walk keeps its place and waits until [resume] is called.
     * Cancelling the collector instead stops it.
     */
    class Paused internal constructor(
        val examined: Int,
        private val answer: CompletableDeferred<Resume>,
    ) : SearchEvent {
        fun resume(choice: Resume) {
            answer.complete(choice)
        }
    }

    /** The last event of a search that was not cancelled. */
    data class Finished(val outcome: SearchOutcome, val examined: Int) : SearchEvent
}

/** Filename search. Implementation: DefaultSearchEngine. */
interface SearchEngine {
    /**
     * Emits [SearchEvent]s as the search goes, ending with [SearchEvent.Finished]. Cancelling
     * the collector stops the search, including while it is paused.
     *
     * [query] is matched case-insensitively as a substring; with '*' or '?' wildcards it must
     * match the whole name. A walk pauses after each round of searching unless [toTheEnd].
     */
    fun search(scope: SearchScope, query: String, toTheEnd: Boolean = false): Flow<SearchEvent>
}
