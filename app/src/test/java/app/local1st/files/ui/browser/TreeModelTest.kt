package app.local1st.files.ui.browser

import app.local1st.files.core.fs.XEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TreeModelTest {

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
