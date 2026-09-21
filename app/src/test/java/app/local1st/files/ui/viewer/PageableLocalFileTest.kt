package app.local1st.files.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageableLocalFileTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyWritableFileIsPageableFromTheList() {
        val file = temporaryFolder.newFile("empty.txt")
        assertTrue(file.length() == 0L)
        assertTrue(file.canWrite())
        assertTrue(isPageableLocalFile(file, allowEmpty = false))
    }

    @Test
    fun emptyReadOnlyFileStreamsUnlessAllowEmpty() {
        val file = temporaryFolder.newFile("proc-like.txt")
        assertTrue(file.setWritable(false, false))
        assertFalse(file.canWrite())
        assertFalse(isPageableLocalFile(file, allowEmpty = false))
        assertTrue(isPageableLocalFile(file, allowEmpty = true))
    }

    @Test
    fun nonEmptyReadOnlyFileIsStillPageable() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("hello")
        assertTrue(file.setWritable(false, false))
        assertFalse(file.canWrite())
        assertTrue(isPageableLocalFile(file, allowEmpty = false))
    }

    @Test
    fun missingPathIsNotPageable() {
        val missing = temporaryFolder.root.resolve("gone.txt")
        assertFalse(missing.exists())
        assertFalse(isPageableLocalFile(missing, allowEmpty = true))
    }
}
