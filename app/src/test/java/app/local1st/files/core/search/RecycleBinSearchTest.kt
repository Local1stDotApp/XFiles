package app.local1st.files.core.search

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecycleBinSearchTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun searchSkipsTheRecycleBin() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        File(root, "keep").writeText("a")
        val bucket = File(root, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, "secret").writeText("b")
        val nested = File(root, "Download/.xfiles-trash").apply { mkdirs() }
        File(nested, "visible").writeText("c")
        val engine = DefaultSearchEngine(
            FsRegistry().apply { register(LocalFileSystem()) },
            volumeRoots = { listOf(root.absolutePath) },
        )
        val rootEntry = XEntry(
            id = XId.file(root.absolutePath),
            name = root.name,
            isDir = true,
            kind = EntryKind.DIR,
        )

        val textHits = engine.hits(rootEntry, "keep").map { it.entry.name }
        assertEquals(listOf("keep"), textHits)
        assertTrue(engine.hits(rootEntry, "secret").isEmpty())
        assertEquals(listOf("visible"), engine.hits(rootEntry, "visible").map { it.entry.name })
        assertEquals(
            listOf(".xfiles-trash"),
            engine.hits(rootEntry, "xfiles").map { it.entry.name },
        )
        val binEntry = XEntry(
            id = XId.file(File(root, ".xfiles-trash").absolutePath),
            name = ".xfiles-trash",
            isDir = true,
            kind = EntryKind.DIR,
        )
        assertEquals(listOf("secret"), engine.hits(binEntry, "secret").map { it.entry.name })
    }

    @Test
    fun searchSkipsABinWhoseVolumeIsMissingFromTheSnapshot() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        val other = temporaryFolder.newFolder("other")
        File(root, "keep").writeText("a")
        val nested = File(root, ".xfiles-trash/files/abc-00000001/photos").apply { mkdirs() }
        File(nested, "secret.txt").writeText("b")
        val engine = DefaultSearchEngine(
            FsRegistry().apply { register(LocalFileSystem()) },
            volumeRoots = { listOf(other.absolutePath) },
        )
        val rootEntry = XEntry(
            id = XId.file(root.absolutePath),
            name = root.name,
            isDir = true,
            kind = EntryKind.DIR,
        )

        assertEquals(listOf("keep"), engine.hits(rootEntry, "keep").map { it.entry.name })
        assertTrue(engine.hits(rootEntry, "secret").isEmpty())
    }

    @Test
    fun searchReadsMountRootsOnce() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        repeat(20) { index -> File(root, "note$index").writeText("x") }
        var calls = 0
        val engine = DefaultSearchEngine(
            FsRegistry().apply { register(LocalFileSystem()) },
            volumeRoots = {
                calls += 1
                listOf(root.absolutePath)
            },
        )
        val rootEntry = XEntry(
            id = XId.file(root.absolutePath),
            name = root.name,
            isDir = true,
            kind = EntryKind.DIR,
        )

        assertEquals(20, engine.hits(rootEntry, "note").size)
        assertEquals(1, calls)
    }

    @Test
    fun searchDoesNotWalkASymlinkIntoTheBin() = runBlocking {
        val root = temporaryFolder.newFolder("vol")
        val bucket = File(root, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, "secret").writeText("b")
        Files.createSymbolicLink(File(root, "link").toPath(), bucket.toPath())
        val roots = listOf(root.absolutePath)
        val engine = DefaultSearchEngine(
            FsRegistry().apply { register(LocalFileSystem(volumeRoots = { roots })) },
            volumeRoots = { roots },
        )
        val rootEntry = XEntry(
            id = XId.file(root.absolutePath),
            name = root.name,
            isDir = true,
            kind = EntryKind.DIR,
        )

        assertTrue(engine.hits(rootEntry, "secret").isEmpty())
    }
}
