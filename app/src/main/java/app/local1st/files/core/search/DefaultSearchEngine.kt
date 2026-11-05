package app.local1st.files.core.search

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.TrashPaths
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Filename search by breadth-first traversal, with [index] answering for the volumes it covers
 * when the scope is all storage.
 *
 * A walk runs in rounds of [roundNanos]. After each it emits [SearchEvent.Paused] and waits
 * for the user, keeping its place. Breadth-first, a round covers the shallow levels of every
 * folder before any deep one, so its hits say whether another round is worth it.
 *
 * A search stops after [maxHits] matches. Inaccessible directories are skipped; archives
 * larger than [MAX_ARCHIVE_BYTES] are not descended into.
 */
class DefaultSearchEngine(
    private val registry: FsRegistry,
    private val volumeRoots: () -> List<String> = { emptyList() },
    /** Null, as below Android 11: a search of all storage walks every volume. */
    private val index: FileNameIndex? = null,
    private val clock: () -> Long = System::nanoTime,
    private val roundNanos: Long = ROUND_NANOS,
    private val maxHits: Int = MAX_HITS,
) : SearchEngine {

    /** What a walk may enter. */
    private class Rules(
        val allowBin: Boolean,
        val intoArchives: Boolean,
        /** Lowercased folder paths a name can match but the walk does not enter. */
        val skipped: Set<String> = emptySet(),
    )

    /** A folder waiting to be opened. [listedBy] is null for a root of the walk. */
    private class Queued(val dir: XEntry, val listedBy: XFileSystem?)

    /** Counts and round timing shared by every part of one search. */
    private inner class Run(toTheEnd: Boolean) {
        var examined = 0
        var hits = 0
        var pauses = !toTheEnd
        var roundStart = clock()
        var lastProgress = roundStart
    }

    override fun search(scope: SearchScope, query: String, toTheEnd: Boolean): Flow<SearchEvent> = flow {
        val matcher = buildMatcher(query)
        // One mount read for the whole search. A pending snapshot scans StorageManager,
        // and doing that once per file stalls a large search.
        val mounts = volumeRoots()
        val run = Run(toTheEnd)
        val complete = when (scope) {
            is SearchScope.Subtree -> {
                val root = scope.root
                val rootPath = root.localPath ?: root.path
                val allowBin = root.scheme == XId.SCHEME_TRASH ||
                    (root.scheme == XId.SCHEME_FILE && TrashPaths.isInsideVolumeBin(rootPath, mounts))
                walk(listOf(root), matcher, mounts, Rules(allowBin, intoArchives = true), run)
            }
            SearchScope.AllStorage -> {
                val covered = index?.let { coveredRoots(it, mounts) }.orEmpty()
                val answered = if (covered.isEmpty()) emptySet() else fromIndex(query, matcher, covered, mounts, run)
                answered != null &&
                    walk(
                        mounts.filter { it !in answered }.map(::volumeEntry),
                        matcher,
                        mounts,
                        Rules(allowBin = false, intoArchives = false, skipped = appDataFolders(mounts)),
                        run,
                    )
            }
        }
        val outcome = if (complete) SearchOutcome.COMPLETE else SearchOutcome.TOO_MANY_HITS
        emit(SearchEvent.Finished(outcome, run.examined))
    }.flowOn(Dispatchers.IO)

    /** An index that cannot say is no index: its volumes are walked. */
    private fun coveredRoots(index: FileNameIndex, mounts: List<String>): List<String> = try {
        index.coveredRoots(mounts).let { covered -> mounts.filter { it in covered } }
    } catch (_: RuntimeException) {
        emptyList()
    }

    /**
     * Matches [index] knows below [roots], statted the way a listing would be. Returns the
     * roots it answered for, which then need no walk, or null once [maxHits] is passed.
     */
    private suspend fun FlowCollector<SearchEvent>.fromIndex(
        query: String,
        matcher: (String) -> Boolean,
        roots: List<String>,
        mounts: List<String>,
        run: Run,
    ): Set<String>? {
        val index = index ?: return emptySet()
        val paths = try {
            // One past what is left, to tell "exactly the cap" from "more than the cap".
            index.matchingPaths(query, roots, limit = maxHits - run.hits + 1)
        } catch (_: RuntimeException) {
            // A provider that rejects the query, or a revoked permission: walk instead.
            return emptySet()
        }
        val local = registry.forScheme(XId.SCHEME_FILE)
        var batch = ArrayList<SearchHit>()
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            // A row can be stale. The stat also brings the bin and symlink checks a listing has.
            val entry = local.stat(XId.file(path)) ?: continue
            if (isDeniedPath(entry, allowBin = false, mounts) || !matcher(entry.name)) continue
            if (run.hits == maxHits) {
                if (batch.isNotEmpty()) emit(SearchEvent.Hits(batch))
                return null
            }
            run.hits++
            batch.add(SearchHit(entry = entry, parentId = XId.file(entry.path.substringBeforeLast('/'))))
            if (batch.size == INDEX_BATCH) {
                emit(SearchEvent.Hits(batch))
                batch = ArrayList()
            }
        }
        if (batch.isNotEmpty()) emit(SearchEvent.Hits(batch))
        return roots.toSet()
    }

    /** Breadth-first below [roots]. False once [maxHits] is passed. */
    private suspend fun FlowCollector<SearchEvent>.walk(
        roots: List<XEntry>,
        matcher: (String) -> Boolean,
        mounts: List<String>,
        rules: Rules,
        run: Run,
    ): Boolean {
        val queue = ArrayDeque<Queued>()
        val visitedContainers = HashSet<String>()
        for (root in roots) {
            if (root.isContainer && !isDeniedPath(root, rules.allowBin, mounts) &&
                visitedContainers.add(containerKey(root))
            ) {
                queue.addLast(Queued(root, listedBy = null))
            }
        }

        while (queue.isNotEmpty()) {
            // A long match-less walk never suspends via emit; check cancellation explicitly.
            currentCoroutineContext().ensureActive()
            if (run.pauses && clock() - run.roundStart >= roundNanos) {
                val answer = CompletableDeferred<Resume>()
                emit(SearchEvent.Paused(run.examined, answer))
                if (answer.await() == Resume.TO_THE_END) run.pauses = false
                run.roundStart = clock()
            }
            val next = queue.removeFirst()
            val dir = next.dir
            // Opening a folder counts too, not just the names it lists. Breadth-first, the last
            // levels are often all empty folders, and the count would stand still through them.
            run.examined++
            val fs = registry.forEntry(dir)
            val children = try {
                // A row another file system listed, such as a bin item, still gets every check.
                fs.listForSearch(dir, fromOwnListing = next.listedBy === fs)
            } catch (_: IOException) {
                continue // inaccessible directory: skip and keep walking
            }

            val batch = ArrayList<SearchHit>()
            for (child in children) {
                run.examined++
                if (isDeniedPath(child, rules.allowBin, mounts)) continue

                if (matcher(child.name)) {
                    if (run.hits == maxHits) {
                        if (batch.isNotEmpty()) emit(SearchEvent.Hits(batch))
                        return false
                    }
                    run.hits++
                    batch.add(SearchHit(entry = child, parentId = dir.id))
                }

                // Queued in listing order, so each level comes out in the order it was listed.
                if (shouldDescend(child, rules, mounts) && visitedContainers.add(containerKey(child))) {
                    queue.addLast(Queued(child, listedBy = fs))
                }
            }
            if (batch.isNotEmpty()) emit(SearchEvent.Hits(batch))
            val now = clock()
            if (now - run.lastProgress >= PROGRESS_NANOS) {
                emit(SearchEvent.Progress(run.examined))
                run.lastProgress = now
            }
        }
        return true
    }

    private fun shouldDescend(entry: XEntry, rules: Rules, roots: List<String>): Boolean = when {
        isDeniedPath(entry, rules.allowBin, roots) -> false
        // A document tree is walked in rounds like local storage. Each folder is a call into
        // the provider's app, often a network request, so a round just covers fewer of them.
        // Its ids grow by one document per level; the cap bounds a provider that never ends.
        entry.scheme == XId.SCHEME_SAF ->
            entry.isDir && XId.safDocumentIds(entry.id).size < MAX_DOCUMENT_DEPTH
        entry.isDir -> entry.scheme != XId.SCHEME_FILE ||
            rules.skipped.isEmpty() || entry.path.lowercase(Locale.ROOT) !in rules.skipped
        entry.kind == EntryKind.ARCHIVE -> rules.intoArchives && entry.size in 0 until MAX_ARCHIVE_BYTES
        else -> false
    }

    /**
     * A document tree id is the chain of document ids down to a folder, so a provider that
     * shows one folder under another (a link, an alias remote) gives it a new id at each
     * level. The provider's own document id is the folder, and keeps such a loop finite.
     */
    private fun containerKey(entry: XEntry): String =
        if (entry.scheme == XId.SCHEME_SAF) {
            val document = XId.safDocumentIds(entry.id).lastOrNull().orEmpty()
            "${XId.SCHEME_SAF}:${XId.safLocationId(entry.id)}:$document"
        } else {
            entry.id
        }

    private fun isDeniedPath(entry: XEntry, allowBin: Boolean, roots: List<String>): Boolean {
        if (entry.scheme != XId.SCHEME_FILE) return false
        val path = entry.path
        // LocalFileSystem clears localPath on a row that resolves into a bin, so only
        // those rows pay for the realpath check. A long walk visits a great many rows.
        if (!allowBin && roots.isNotEmpty() &&
            (TrashPaths.isConcealedBinPath(path, roots) ||
                (entry.localPath == null && TrashPaths.crossesVolumeBin(path, roots)))
        ) {
            return true
        }
        return DENIED_PREFIXES.any { path == it || path.startsWith("$it/") }
    }

    /** Substring match, or whole-name wildcard match when the query contains '*' / '?'. */
    private fun buildMatcher(query: String): (String) -> Boolean {
        if ('*' !in query && '?' !in query) {
            return { name -> name.contains(query, ignoreCase = true) }
        }
        val pattern = StringBuilder(query.length + 8)
        for (c in query) {
            when (c) {
                '*' -> pattern.append(".*")
                '?' -> pattern.append('.')
                in REGEX_METACHARS -> pattern.append('\\').append(c)
                else -> pattern.append(c)
            }
        }
        val regex = Regex(pattern.toString(), RegexOption.IGNORE_CASE)
        return { name -> regex.matches(name) }
    }

    companion object {
        /** Most searches finish within one round; a slow one asks before it goes on. */
        const val ROUND_NANOS = 10_000_000_000L
        const val MAX_HITS = 5_000
        private const val PROGRESS_NANOS = 250_000_000L
        private const val INDEX_BATCH = 64
        private const val MAX_ARCHIVE_BYTES = 100L * 1024 * 1024
        private const val MAX_DOCUMENT_DEPTH = 64
        private val DENIED_PREFIXES = listOf("/proc", "/sys", "/dev")
        private const val REGEX_METACHARS = "\\^$.|+()[]{}"

        private fun volumeEntry(path: String) = XEntry(
            id = XId.file(path),
            name = path.substringAfterLast('/').ifEmpty { path },
            isDir = true,
            kind = EntryKind.DIR,
            localPath = path,
        )

        /** Out of a phone-wide search: no index has them, and they hold app caches. */
        private fun appDataFolders(mounts: List<String>): Set<String> =
            mounts.flatMapTo(HashSet()) { root ->
                val base = root.trimEnd('/')
                listOf("$base/Android/data", "$base/Android/obb").map { it.lowercase(Locale.ROOT) }
            }
    }
}
