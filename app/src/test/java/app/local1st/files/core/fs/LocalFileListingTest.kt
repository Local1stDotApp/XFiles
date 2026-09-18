package app.local1st.files.core.fs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileListingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fs = LocalFileSystem()

    @Test
    fun directoryListingIncludesChildCountAndMtime() {
        val parent = temporaryFolder.newFolder("docs")
        val nested = File(parent, "nested").apply { mkdir() }
        File(nested, "c.txt").writeText("c")
        File(nested, "d.txt").writeText("d")
        File(parent, "empty").mkdir()

        val kids = fs.list(entry(parent, isDir = true))

        val nestedEntry = kids.single { it.name == "nested" }
        assertTrue(nestedEntry.isDir)
        assertEquals(2, nestedEntry.childCountHint)
        assertTrue(nestedEntry.mtime > 0)

        assertEquals(0, kids.single { it.name == "empty" }.childCountHint)
    }

    @Test
    fun directoryChildCountSeparatesHiddenNames() {
        val parent = temporaryFolder.newFolder("docs")
        val nested = File(parent, "nested").apply { mkdir() }
        File(nested, "c.txt").writeText("c")
        File(nested, ".secret").writeText("s")
        File(nested, "d.txt").writeText("d")

        val nestedEntry = fs.list(entry(parent, isDir = true)).single { it.name == "nested" }
        assertEquals(3, nestedEntry.childCountHint)
        assertEquals(1, nestedEntry.hiddenChildCountHint)
    }

    @Test
    fun statAndRenameDoNotReaddirForFolderCounts() {
        val parent = temporaryFolder.newFolder("docs")
        val nested = File(parent, "nested").apply { mkdir() }
        File(nested, "c.txt").writeText("c")
        File(nested, "d.txt").writeText("d")

        val listed = fs.list(entry(parent, isDir = true)).single { it.name == "nested" }
        assertEquals(2, listed.childCountHint)
        assertEquals(-1, fs.stat(XId.file(nested.absolutePath))!!.childCountHint)

        val renamed = fs.rename(entry(nested, isDir = true), "nested2")
        assertEquals("nested2", renamed.name)
        assertEquals(-1, renamed.childCountHint)
    }

    private fun entry(file: File, isDir: Boolean) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = isDir,
        localPath = file.absolutePath,
    )
}
