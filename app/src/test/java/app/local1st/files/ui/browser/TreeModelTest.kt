package app.local1st.files.ui.browser

import app.local1st.files.core.fs.XEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TreeModelTest {

    @Test
    fun fileMetadataReplacesSizeAndTimeAndKeepsTheRest() {
        val pinned = XEntry(
            id = "file:///docs/a.txt",
            name = "a.txt",
            isDir = false,
            size = 4,
            mtime = 1_000,
            pinned = true,
        )
        val other = XEntry(id = "file:///docs/b.txt", name = "b.txt", isDir = false, size = 2, mtime = 2_000)
        val listed = listOf(pinned, other)

        val updated = listed.withFileMetadata(pinned.id, size = 18, mtime = 9_000)

        assertEquals(18, updated[0].size)
        assertEquals(9_000, updated[0].mtime)
        assertEquals("a.txt", updated[0].name)
        assertEquals(true, updated[0].pinned)
        assertSame(other, updated[1])
    }

    @Test
    fun fileMetadataKeepsTheListWhenNothingChanged() {
        val file = XEntry(id = "file:///docs/a.txt", name = "a.txt", isDir = false, size = 4, mtime = 1_000)
        val listed = listOf(file)

        assertSame(listed, listed.withFileMetadata("file:///docs/missing.txt", size = 1, mtime = 2))
        assertSame(listed, listed.withFileMetadata(file.id, size = 4, mtime = 1_000))
    }

    @Test
    fun fileMetadataGivesAFolderOnlyTheTime() {
        val dir = XEntry(id = "file:///docs", name = "docs", isDir = true, size = 7, mtime = 1_000, childCountHint = 3)
        val listed = listOf(dir)

        val updated = listed.withFileMetadata(dir.id, size = -1, mtime = 9_000)

        assertEquals(dir.copy(mtime = 9_000), updated[0])
        assertSame(listed, listed.withFileMetadata(dir.id, size = -1, mtime = 1_000))
    }

    @Test
    fun fileMetadataUpdatesOnlyTheDirectoryThatContainsTheFile() {
        val file = XEntry(id = "file:///docs/a.txt", name = "a.txt", isDir = false, size = 4, mtime = 1_000)
        val docs = listOf(file)
        val other = listOf(XEntry(id = "file:///pics/a.jpg", name = "a.jpg", isDir = false, size = 8, mtime = 3_000))
        val children = mapOf("file:///docs" to docs, "file:///pics" to other)

        val updated = children.withFileMetadata(file.id, size = 18, mtime = 9_000)

        assertEquals(18, updated.getValue("file:///docs")[0].size)
        assertEquals(9_000, updated.getValue("file:///docs")[0].mtime)
        assertSame(other, updated.getValue("file:///pics"))
        assertSame(children, children.withFileMetadata("file:///nowhere", size = 1, mtime = 2))
    }

    @Test
    fun listedChildCountReplacesAStaleHint() {
        val dir = XEntry(id = "file:///docs", name = "docs", isDir = true, childCountHint = 1)
        val listed = listOf(
            XEntry(id = "file:///docs/a", name = "a", isDir = false),
            XEntry(id = "file:///docs/b", name = "b", isDir = false),
        )

        assertEquals(2, dir.withListedChildCount(listed, showHidden = true).childCountHint)
    }

    @Test
    fun listedChildCountSkipsHiddenChildrenUnlessShown() {
        val dir = XEntry(id = "file:///docs", name = "docs", isDir = true, childCountHint = 3)
        val listed = listOf(
            XEntry(id = "file:///docs/a", name = "a", isDir = false),
            XEntry(id = "file:///docs/.secret", name = ".secret", isDir = false, hidden = true),
            XEntry(id = "file:///docs/b", name = "b", isDir = false),
        )

        assertEquals(2, dir.withListedChildCount(listed, showHidden = false).childCountHint)
        assertEquals(3, dir.withListedChildCount(listed, showHidden = true).childCountHint)
        val alreadyVisible = dir.copy(childCountHint = 2)
        assertSame(alreadyVisible, alreadyVisible.withListedChildCount(listed, showHidden = false))
    }

    @Test
    fun unlistedChildCountSkipsHiddenUnlessShown() {
        val dir = XEntry(
            id = "file:///docs",
            name = "docs",
            isDir = true,
            childCountHint = 3,
            hiddenChildCountHint = 1,
        )

        assertEquals(2, dir.withListedChildCount(null, showHidden = false).childCountHint)
        assertEquals(3, dir.withListedChildCount(null, showHidden = true).childCountHint)
        val visibleOnly = dir.copy(childCountHint = 2, hiddenChildCountHint = 0)
        assertSame(visibleOnly, visibleOnly.withListedChildCount(null, showHidden = false))
    }

    @Test
    fun listedChildCountLeavesFilesAndBadgedRowsAlone() {
        val file = XEntry(id = "file:///a.txt", name = "a.txt", isDir = false)
        val volume = XEntry(
            id = "file:///storage",
            name = "Internal",
            isDir = true,
            badge = "12 GB free",
            childCountHint = -1,
        )
        val listed = listOf(XEntry(id = "file:///storage/DCIM", name = "DCIM", isDir = true))

        assertSame(file, file.withListedChildCount(listed, showHidden = false))
        assertSame(volume, volume.withListedChildCount(listed, showHidden = false))
        assertSame(file, file.withListedChildCount(null, showHidden = false))
    }
}
