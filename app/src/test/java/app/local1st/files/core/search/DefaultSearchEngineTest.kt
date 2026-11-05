package app.local1st.files.core.search

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultSearchEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // Fake time in which every folder listing takes one second.
    private val now = AtomicLong(0)
    private val listings = AtomicInteger(0)

    private fun engine(roundSeconds: Long? = null, maxHits: Int = DefaultSearchEngine.MAX_HITS) =
        DefaultSearchEngine(
            FsRegistry().apply {
                register(
                    ObservedFileSystem(LocalFileSystem()) {
                        listings.incrementAndGet()
                        now.addAndGet(SECOND)
                    },
                )
            },
            clock = { now.get() },
            roundNanos = roundSeconds?.times(SECOND) ?: Long.MAX_VALUE,
            maxHits = maxHits,
        )

    /** `vol/f0 … vol/f<n-1>`, each holding one `match-<i>`. Listing it all takes n + 1 seconds. */
    private fun folders(n: Int): XEntry {
        val root = temporaryFolder.newFolder("vol")
        repeat(n) { i -> File(root, "f$i/match-$i").apply { parentFile!!.mkdir() }.writeText("x") }
        return dir(root)
    }

    private fun dir(file: File) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = true,
        kind = EntryKind.DIR,
        localPath = file.absolutePath,
    )

    @Test
    fun onlyAFolderItsOwnFileSystemListedSkipsItsChecks() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        File(root, "a/b").mkdirs()
        val rootRow = dir(root)
        val checksSkipped = mutableMapOf<String, Boolean>()
        val local = LocalFileSystem()
        val files = object : XFileSystem by local {
            override fun listForSearch(dir: XEntry, fromOwnListing: Boolean): List<XEntry> {
                checksSkipped[dir.name] = fromOwnListing
                return local.listForSearch(dir, fromOwnListing)
            }
        }
        // Lists a local folder the way the Recycle Bin lists its items: as file:// rows.
        val bin = object : XFileSystem by local {
            override val scheme = XId.SCHEME_TRASH

            override fun listForSearch(dir: XEntry, fromOwnListing: Boolean) = listOf(rootRow)
        }
        val engine = DefaultSearchEngine(FsRegistry().apply { register(files); register(bin) })
        val expected = mapOf("vol" to false, "a" to true, "b" to true)

        engine.hits(rootRow, "nothing")
        assertEquals(expected, checksSkipped)

        checksSkipped.clear()
        engine.hits(XEntry(id = XId.TRASH_ROOT, name = "Bin", isDir = true), "nothing")
        assertEquals(expected, checksSkipped)
    }

    @Test
    fun aPausedWalkContinuesWhereItStopped() = runBlocking {
        val root = folders(6)
        val events = mutableListOf<SearchEvent>()

        engine(roundSeconds = 3).search(SearchScope.Subtree(root), "match").collect { event ->
            events += event
            if (event is SearchEvent.Paused) event.resume(Resume.NEXT_ROUND)
        }

        val names = events.filterIsInstance<SearchEvent.Hits>().flatMap { it.hits }.map { it.entry.name }
        assertEquals((0 until 6).map { "match-$it" }.sorted(), names.sorted())
        // Each folder was read once: continuing did not start over.
        assertEquals(7, listings.get())
        // Rounds end after three listings: the root and two folders, then three more folders.
        // Each folder counts once opened and each entry once listed.
        assertEquals(listOf(11, 17), events.filterIsInstance<SearchEvent.Paused>().map { it.examined })
        assertEquals(SearchEvent.Finished(SearchOutcome.COMPLETE, examined = 19), events.last())
    }

    @Test
    fun toTheEndNeverPauses() = runBlocking {
        val root = folders(6)

        val events = engine(roundSeconds = 1)
            .search(SearchScope.Subtree(root), "match", toTheEnd = true)
            .toList()

        assertTrue(events.none { it is SearchEvent.Paused })
        assertEquals(6, events.filterIsInstance<SearchEvent.Hits>().sumOf { it.hits.size })
        assertEquals(SearchOutcome.COMPLETE, (events.last() as SearchEvent.Finished).outcome)
    }

    @Test
    fun choosingToTheEndAtAPauseStopsAsking() = runBlocking {
        val root = folders(6)
        var pauses = 0

        engine(roundSeconds = 2).search(SearchScope.Subtree(root), "match").collect { event ->
            if (event is SearchEvent.Paused) {
                pauses++
                event.resume(Resume.TO_THE_END)
            }
        }

        assertEquals(1, pauses)
        assertEquals(7, listings.get())
    }

    @Test
    fun cancellingAPausedWalkReadsNothingMore() = runBlocking {
        val root = folders(6)
        val paused = CompletableDeferred<Unit>()
        val job = launch {
            engine(roundSeconds = 2).search(SearchScope.Subtree(root), "match").collect { event ->
                if (event is SearchEvent.Paused) paused.complete(Unit)
            }
        }
        withTimeout(TIMEOUT_MS) { paused.await() }
        val listedBeforeCancel = listings.get()

        job.cancel()
        withTimeout(TIMEOUT_MS) { job.join() }

        assertEquals(2, listedBeforeCancel)
        assertEquals(listedBeforeCancel, listings.get())
    }

    @Test
    fun shallowMatchesComeBeforeDeepOnes() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        // One match per depth. However each folder happens to list, breadth-first reports
        // depth 1 first and depth 5 last.
        for (path in listOf("a/b/c/d/match-4", "a/b/match-2", "z/match-1", "match-0", "a/b/c/match-3")) {
            File(root, path).apply { parentFile!!.mkdirs() }.writeText("x")
        }

        val depths = engine().hits(dir(root), "match").map { hit ->
            hit.entry.path.removePrefix(root.absolutePath).count { it == '/' }
        }

        assertEquals(listOf(1, 2, 3, 4, 5), depths)
    }

    @Test
    fun moreMatchesThanTheCapStopAndSaySo() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        repeat(5) { File(root, "match-$it").writeText("x") }

        val events = engine(maxHits = 3).search(SearchScope.Subtree(dir(root)), "match").toList()

        assertEquals(3, events.filterIsInstance<SearchEvent.Hits>().sumOf { it.hits.size })
        assertEquals(SearchOutcome.TOO_MANY_HITS, (events.last() as SearchEvent.Finished).outcome)
    }

    @Test
    fun exactlyTheCapIsStillComplete() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        repeat(3) { File(root, "match-$it").writeText("x") }

        val events = engine(maxHits = 3).search(SearchScope.Subtree(dir(root)), "match").toList()

        assertEquals(3, events.filterIsInstance<SearchEvent.Hits>().sumOf { it.hits.size })
        assertEquals(SearchOutcome.COMPLETE, (events.last() as SearchEvent.Finished).outcome)
    }

    @Test
    fun progressCountsWhatWasLookedAt() = runBlocking {
        val root = folders(3)

        val progress = engine().search(SearchScope.Subtree(root), "no such name")
            .filterIsInstance<SearchEvent.Progress>()
            .toList()
            .map { it.examined }

        // The root and its three folders, then each folder and its file.
        assertEquals(listOf(4, 6, 8, 10), progress)
    }

    @Test
    fun progressKeepsMovingThroughEmptyFolders() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        repeat(3) { File(root, "empty-$it").mkdir() }

        val progress = engine().search(SearchScope.Subtree(dir(root)), "no such name")
            .filterIsInstance<SearchEvent.Progress>()
            .toList()
            .map { it.examined }

        // Every name is listed before the first empty folder is opened. Opening each one
        // still counts, so the number does not stand still.
        assertEquals(listOf(4, 5, 6, 7), progress)
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val TIMEOUT_MS = 5_000L
    }
}
