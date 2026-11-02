package app.local1st.files.core.search

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import java.io.File
import java.util.Collections
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AllStorageSearchTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Folders the walk listed. */
    private val listed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun engine(
        volumes: List<File>,
        index: FileNameIndex?,
        maxHits: Int = DefaultSearchEngine.MAX_HITS,
        // Rows to add to a listing, e.g. an archive the walk must not open.
        extra: (XEntry) -> List<XEntry> = { emptyList() },
    ): DefaultSearchEngine {
        val roots = volumes.map { it.absolutePath }
        val local = LocalFileSystem(volumeRoots = { roots })
        val fs = object : XFileSystem by local {
            override fun listForSearch(dir: XEntry): List<XEntry> {
                listed += dir.path
                return local.listForSearch(dir) + extra(dir)
            }
        }
        return DefaultSearchEngine(
            FsRegistry().apply { register(fs) },
            volumeRoots = { roots },
            index = index,
            maxHits = maxHits,
        )
    }

    private class FakeIndex(private val covered: Set<String>, private val paths: List<String>) : FileNameIndex {
        override fun coveredRoots(volumeRoots: List<String>) = covered

        override fun matchingPaths(query: String, roots: List<String>, limit: Int) = paths.take(limit)

        override fun addFolder(path: String) = Unit
    }

    private fun File.file(path: String) = File(this, path).apply {
        parentFile!!.mkdirs()
        writeText("x")
    }

    @Test
    fun theIndexAnswersForItsVolumesAndTheOthersAreWalked() = runBlocking {
        val phone = temporaryFolder.newFolder("phone")
        val usb = temporaryFolder.newFolder("usb")
        val fresh = phone.file("DCIM/match-photo")
        val renamed = phone.file("Download/other")
        usb.file("match-usb")
        val index = FakeIndex(
            covered = setOf(phone.absolutePath),
            paths = listOf(
                fresh.absolutePath,
                File(phone, "gone/match-deleted").absolutePath, // a stale row
                renamed.absolutePath, // a row whose file no longer has that name
            ),
        )

        val hits = engine(listOf(phone, usb), index).allStorageHits("match")

        assertEquals(listOf("match-photo", "match-usb"), hits.map { it.entry.name })
        assertEquals(XId.file(fresh.parentFile!!.absolutePath), hits.first().parentId)
        // The indexed volume was never walked.
        assertEquals(listOf(usb.absolutePath), listed.toList())
    }

    @Test
    fun aRecycleBinRowFromTheIndexIsDropped() = runBlocking {
        val phone = temporaryFolder.newFolder("phone")
        val trashed = phone.file(".xfiles-trash/files/abc-00000001/match")
        val index = FakeIndex(setOf(phone.absolutePath), listOf(trashed.absolutePath))

        assertTrue(engine(listOf(phone), index).allStorageHits("match").isEmpty())
    }

    @Test
    fun aFailingIndexIsWalkedInstead() = runBlocking {
        val phone = temporaryFolder.newFolder("phone")
        phone.file("Music/match")
        val index = object : FileNameIndex {
            override fun coveredRoots(volumeRoots: List<String>) = volumeRoots.toSet()

            override fun matchingPaths(query: String, roots: List<String>, limit: Int): List<String> =
                throw SecurityException("all-files access revoked")

            override fun addFolder(path: String) = Unit
        }

        assertEquals(listOf("match"), engine(listOf(phone), index).allStorageHits("match").map { it.entry.name })
    }

    @Test
    fun withoutAnIndexEveryVolumeIsWalkedButNotAppDataOrArchives() = runBlocking {
        val phone = temporaryFolder.newFolder("phone")
        val sd = temporaryFolder.newFolder("sd")
        phone.file("Android/data/com.example/cache/match-cache")
        phone.file("Android/obb/com.example/match-obb")
        phone.file("Android/media/com.example/match-media")
        sd.file("match-sd")
        // Opening it would need the zip filesystem, which this registry lacks.
        val archive = XEntry(
            id = XId.file("${sd.absolutePath}/match.zip"),
            name = "match.zip",
            isDir = false,
            kind = EntryKind.ARCHIVE,
            size = 10L,
            localPath = "${sd.absolutePath}/match.zip",
        )
        val engine = engine(listOf(phone, sd), index = null) { dir ->
            if (dir.path == sd.absolutePath) listOf(archive) else emptyList()
        }

        val names = engine.allStorageHits("match").map { it.entry.name }

        assertEquals(setOf("match-media", "match-sd", "match.zip"), names.toSet())
        assertTrue(listed.none { "/Android/data" in it || "/Android/obb" in it })
    }

    @Test
    fun theCapCountsIndexAndWalkTogether() = runBlocking {
        val phone = temporaryFolder.newFolder("phone")
        val usb = temporaryFolder.newFolder("usb")
        val indexed = listOf(phone.file("match-1"), phone.file("match-2"))
        usb.file("match-3")
        usb.file("match-4")
        val index = FakeIndex(setOf(phone.absolutePath), indexed.map { it.absolutePath })

        val events = engine(listOf(phone, usb), index, maxHits = 3)
            .search(SearchScope.AllStorage, "match", toTheEnd = true)
            .toList()

        assertEquals(3, events.filterIsInstance<SearchEvent.Hits>().sumOf { it.hits.size })
        assertEquals(SearchOutcome.TOO_MANY_HITS, (events.last() as SearchEvent.Finished).outcome)
    }

    @Test
    fun moreIndexMatchesThanTheCapSaySo() = runBlocking {
        val phone = temporaryFolder.newFolder("phone")
        val files = (1..4).map { phone.file("match-$it") }
        val index = FakeIndex(setOf(phone.absolutePath), files.map { it.absolutePath })

        val events = engine(listOf(phone), index, maxHits = 3)
            .search(SearchScope.AllStorage, "match")
            .toList()

        assertEquals(3, events.filterIsInstance<SearchEvent.Hits>().sumOf { it.hits.size })
        assertEquals(SearchOutcome.TOO_MANY_HITS, (events.last() as SearchEvent.Finished).outcome)
        assertTrue(listed.isEmpty())
    }
}
