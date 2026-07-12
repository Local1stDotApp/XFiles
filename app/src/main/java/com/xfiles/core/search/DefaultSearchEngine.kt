package com.xfiles.core.search

import com.xfiles.core.fs.EntryKind
import com.xfiles.core.fs.FsRegistry
import com.xfiles.core.fs.XEntry
import com.xfiles.core.fs.XId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Filename search via iterative depth-first traversal below a root container.
 *
 * Bounds: completes after [MAX_HITS] matches or [MAX_VISITED] examined entries.
 * Inaccessible directories are skipped; archives larger than [MAX_ARCHIVE_BYTES]
 * are not descended into.
 */
class DefaultSearchEngine(private val registry: FsRegistry) : SearchEngine {

    override fun search(root: XEntry, query: String): Flow<SearchHit> = flow {
        val matcher = buildMatcher(query)
        val deque = ArrayDeque<XEntry>()
        val visitedContainers = HashSet<String>()
        var visited = 0
        var hits = 0

        if (root.isContainer && !isDeniedPath(root)) {
            deque.addFirst(root)
            visitedContainers.add(root.id)
        }

        while (deque.isNotEmpty()) {
            // A long match-less walk never suspends via emit; check cancellation explicitly.
            currentCoroutineContext().ensureActive()
            val dir = deque.removeFirst()
            val children = try {
                registry.forEntry(dir).list(dir)
            } catch (_: IOException) {
                continue // inaccessible directory: skip and keep walking
            }

            // Collected first, then pushed in reverse so DFS visits children in listing order.
            val descend = ArrayList<XEntry>()
            for (child in children) {
                if (++visited > MAX_VISITED) return@flow

                if (matcher(child.name)) {
                    emit(SearchHit(entry = child, parentId = dir.id))
                    if (++hits >= MAX_HITS) return@flow
                }

                if (shouldDescend(child) && visitedContainers.add(child.id)) {
                    descend.add(child)
                }
            }
            for (i in descend.indices.reversed()) {
                deque.addFirst(descend[i])
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun shouldDescend(entry: XEntry): Boolean = when {
        isDeniedPath(entry) -> false
        entry.isDir -> true
        entry.kind == EntryKind.ARCHIVE -> entry.size in 0 until MAX_ARCHIVE_BYTES
        else -> false
    }

    private fun isDeniedPath(entry: XEntry): Boolean {
        if (entry.scheme != XId.SCHEME_FILE) return false
        val path = entry.path
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

    private companion object {
        const val MAX_HITS = 500
        const val MAX_VISITED = 50_000
        const val MAX_ARCHIVE_BYTES = 100L * 1024 * 1024
        val DENIED_PREFIXES = listOf("/proc", "/sys", "/dev")
        const val REGEX_METACHARS = "\\^$.|+()[]{}"
    }
}
